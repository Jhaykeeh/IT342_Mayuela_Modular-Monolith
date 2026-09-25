package edu.cit.mayuela.supplier;

import java.time.Duration;
import java.util.List;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.io.IOException;
import org.springframework.stereotype.Component;

/**
 * Thin HTTP + session handling for the LegacySupply partner interface.
 *
 * Responsibilities kept entirely inside this package:
 *  - owns the session token, signs in when needed and signs in again when the
 *    service stops accepting a session (E-AUTH-03 / E-AUTH-07),
 *  - times each request out (3 seconds),
 *  - retries transient failures (network, 5xx, 429) with backoff, at most 3
 *    attempts per call,
 *  - forwards the idempotent X-Request-Id header unchanged.
 */
@Component
class LegacySupplyClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(3);
    private static final int MAX_ATTEMPTS = 3;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);

    private final LegacySupplyProperties properties;
    private final org.slf4j.Logger log = SupplierLogger.get();

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .build();

    private volatile String sessionToken;
    private final Object sessionLock = new Object();

    LegacySupplyClient(LegacySupplyProperties properties) {
        this.properties = properties;
    }

    /** POST /auth/token -> new session token. */
    String login() {
        String body = LegacyXml.authRequest(properties.getClientId(), properties.getApiKey());
        RequestSpec spec = RequestSpec.post("/auth/token", body, null);
        HttpResponse<String> resp = sendRetry(spec, null);
        return LegacyXml.sessionToken(resp.body());
    }

    /** GET /catalog -> raw catalog XML (used by nothing in the app today). */
    String fetchCatalogXml() {
        HttpResponse<String> resp = execute(RequestSpec.get("/catalog"));
        return resp.body();
    }

    /** POST /purchase-orders -> acknowledgement. */
    LegacyXml.Ack placeOrder(String supplierSku, int qty, String buyerRef, String requestId) {
        String body = LegacyXml.purchaseOrder(supplierSku, qty, buyerRef);
        HttpResponse<String> resp = execute(RequestSpec.post("/purchase-orders", body, requestId));
        return LegacyXml.parseAck(resp.body());
    }

    /** GET /purchase-orders/{poNumber} -> current status. */
    LegacyXml.OrderStatus fetchOrder(String poNumber) {
        HttpResponse<String> resp = execute(RequestSpec.get("/purchase-orders/" + poNumber));
        return LegacyXml.parseStatus(resp.body());
    }

    /** GET /purchase-orders?buyerRef=... -> every order under that reference. */
    List<LegacyXml.OrderStatus> findOrdersByBuyerRef(String buyerRef) {
        HttpResponse<String> resp = execute(RequestSpec.get("/purchase-orders?buyerRef=" + buyerRef));
        return LegacyXml.parseOrderList(resp.body());
    }

    // ---- plumbing --------------------------------------------------------

    private String ensureSession() {
        String token = sessionToken;
        if (token != null) {
            return token;
        }
        synchronized (sessionLock) {
            if (sessionToken == null) {
                sessionToken = login();
                log.info("Signed in to LegacySupply (session issued)");
            }
            return sessionToken;
        }
    }

    private HttpResponse<String> execute(RequestSpec spec) {
        int reauthAttempts = 0;
        while (true) {
            String token = ensureSession();
            try {
                HttpResponse<String> resp = sendRetry(spec, token);
                if (resp.statusCode() == 401) {
                    throw authFailure(resp.body());
                }
                return resp;
            } catch (LegacySupplyAuthException e) {
                if (reauthAttempts >= 1) {
                    throw new LegacySupplyException(e.getCode(), e.getMessage(), false);
                }
                reauthAttempts++;
                sessionToken = null;
                log.warn("Session rejected (" + e.getCode() + "); signing in again and retrying");
            }
        }
    }

    /**
     * Sends the request up to three times, sleeping progressively longer
     * between attempts. Throws a retryable LegacySupplyException once attempts
     * are exhausted, a permanent one for clear 4xx business errors.
     */
    private HttpResponse<String> sendRetry(RequestSpec spec, String token) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                HttpResponse<String> resp = spec.send(this, token, http);
                int status = resp.statusCode();
                if (status == 401) {
                    throw authFailure(resp.body());
                }
                if (status == 429 || status >= 500) {
                    if (attempt < MAX_ATTEMPTS) {
                        log.warn("Request failed HTTP " + status + " (" + LegacyXml.errorCode(resp.body())
                                + "), attempt " + attempt + "/" + MAX_ATTEMPTS + " - retrying");
                        sleep(backoff(attempt));
                        continue;
                    }
                    throw new LegacySupplyException("HTTP " + status + " " + LegacyXml.errorCode(resp.body())
                            + " " + LegacyXml.errorMessage(resp.body()), true, null);
                }
                if (status >= 400) {
                    String code = LegacyXml.errorCode(resp.body());
                    String message = LegacyXml.errorMessage(resp.body());
                    throw new LegacySupplyException(code.isBlank() ? "HTTP " + status : code,
                            message.isBlank() ? "HTTP " + status : message, false);
                }
                return resp;
            } catch (HttpTimeoutException e) {
                if (attempt < MAX_ATTEMPTS) {
                    log.warn("Request timed out, attempt " + attempt + "/" + MAX_ATTEMPTS + " - retrying");
                    sleep(backoff(attempt));
                } else {
                    throw new LegacySupplyException("Timed out after " + MAX_ATTEMPTS + " attempts", true, e);
                }
            } catch (IOException | InterruptedException e) {
                if (Thread.currentThread().isInterrupted()) {
                    Thread.currentThread().interrupt();
                }
                if (attempt < MAX_ATTEMPTS) {
                    log.warn("Network error, attempt " + attempt + "/" + MAX_ATTEMPTS + " - retrying: "
                            + e.getMessage());
                    sleep(backoff(attempt));
                } else {
                    throw new LegacySupplyException("Network failure after " + MAX_ATTEMPTS + " attempts", true, e);
                }
            }
        }
        throw new IllegalStateException("unreachable");
    }

    private LegacySupplyAuthException authFailure(String body) {
        String code = LegacyXml.errorCode(body);
        return new LegacySupplyAuthException(code, code.isBlank() ? "HTTP 401" : code);
    }

    private static long backoff(int attempt) {
        return 500L * (1L << (attempt - 1));
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class LegacySupplyAuthException extends LegacySupplyException {
        LegacySupplyAuthException(String code, String message) {
            super(code, message, false);
        }
    }

    /** Mutable description of an HTTP call so a session can be attached late. */
    private static final class RequestSpec {
        private final String method;
        private final String path;
        private final String body;
        private final String requestId;

        private RequestSpec(String method, String path, String body, String requestId) {
            this.method = method;
            this.path = path;
            this.body = body;
            this.requestId = requestId;
        }

        static RequestSpec get(String path) {
            return new RequestSpec("GET", path, null, null);
        }

        static RequestSpec post(String path, String body, String requestId) {
            return new RequestSpec("POST", path, body, requestId);
        }

        HttpResponse<String> send(LegacySupplyClient owner, String session, HttpClient http)
                throws IOException, InterruptedException {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(owner.properties.getBaseUrl() + path))
                    .timeout(TIMEOUT);
            if (session != null) {
                builder.header("X-LS-Session", session);
            }
            if (requestId != null && !requestId.isBlank()) {
                builder.header("X-Request-Id", requestId);
            }
            if ("GET".equals(method)) {
                builder.GET();
            } else {
                builder.header("Content-Type", "application/xml")
                        .POST(HttpRequest.BodyPublishers.ofString(body, java.nio.charset.StandardCharsets.UTF_8));
            }
            return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}