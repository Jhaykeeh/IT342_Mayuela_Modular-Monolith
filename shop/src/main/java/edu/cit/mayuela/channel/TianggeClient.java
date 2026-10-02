package edu.cit.mayuela.channel;

import edu.cit.mayuela.platform.AppInstance;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClient.RequestHeadersSpec;
import org.springframework.web.client.RestClient.ResponseSpec;

/**
 * The only place in the application that speaks to Tiangge.
 *
 * Every method is a thin, intention-revealing wrapper around one endpoint. It
 * adds no retries of its own ({@link Retries} does that), no bookkeeping and no
 * decisions - it turns HTTP into either a value or a
 * {@link TianggeCallFailed}. Business rules live in the callers.
 *
 * All requests carry the three identity headers, applied once in
 * {@link ChannelConfig}, including on every retry.
 */
@Component
class TianggeClient {

    /** Largest page the feed endpoint accepts. */
    static final int FEED_LIMIT = 50;

    /**
     * Pulls the error code out of an error body, either as {@code "code"} or as
     * Tiangge's own {@code "error"} key.
     */
    private static final Pattern ERROR_CODE = Pattern.compile(
            "\"code\"\\s*:\\s*\"([^\"]*)\"|\"error\"\\s*:\\s*\"([^\"]*)\"");

    private static final Pattern ERROR_MESSAGE = Pattern.compile("\"message\"\\s*:\\s*\"([^\"]*)\"");

    private final RestClient http;
    private final Logger log = ChannelLogger.get();

    TianggeClient(RestClient tianggeRestClient) {
        this.http = tianggeRestClient;
    }

    /** POST /instances/heartbeat - the call that declares this instance alive. */
    void heartbeat(String appName) {
        TianggeBodies.Heartbeat body = new TianggeBodies.Heartbeat(
                appName, AppInstance.startedAt().toString(), AppInstance.uptimeSeconds());
        call("POST /instances/heartbeat", http.post().uri("/instances/heartbeat").body(body),
                ResponseSpec::toBodilessEntity);
    }

    /** PUT /listings - replaces the previous set of listings. */
    void replaceListings(List<ChannelProperties.Listing> listings) {
        List<TianggeBodies.Listing> body = new ArrayList<>();
        for (ChannelProperties.Listing listing : listings) {
            body.add(new TianggeBodies.Listing(
                    listing.getSellerSku(), listing.getTitle(), listing.getSupplierSku()));
        }
        call("PUT /listings", http.put().uri("/listings").body(body), ResponseSpec::toBodilessEntity);
    }

    /** PUT /stock - the marketplace's view of what can still be bought. */
    void publishStock(List<TianggeBodies.Stock> stock) {
        call("PUT /stock", http.put().uri("/stock").body(stock), ResponseSpec::toBodilessEntity);
    }

    /** GET /feed - events after the given cursor. */
    TianggeBodies.FeedPage fetchFeed(long after, int limit) {
        TianggeBodies.FeedPage page = call("GET /feed", http.get().uri(builder -> builder
                .path("/feed")
                .queryParam("after", after)
                .queryParam("limit", limit)
                .build()), response -> response.body(TianggeBodies.FeedPage.class));
        return page == null ? new TianggeBodies.FeedPage(List.of(), null) : page;
    }

    /**
     * GET /orders/{orderId} - how Tiangge currently sees one order.
     *
     * Not part of the normal flow; it exists so a stuck order can be inspected
     * without leaving the application. A 404 {@code order_not_found} is turned
     * into null instead of an exception, which is the only sensible answer to
     * "is this order ours?".
     */
    TianggeBodies.OrderView findOrder(String orderId) {
        try {
            return call("GET /orders/" + orderId,
                    http.get().uri("/orders/{orderId}", orderId),
                    response -> response.body(TianggeBodies.OrderView.class));
        } catch (TianggeCallFailed e) {
            if (e.status() == 404) {
                return null;
            }
            throw e;
        }
    }

    /**
     * POST /orders/{orderId}/decision.
     *
     * A 409 {@code decision_conflict} is swallowed: Tiangge only raises it when
     * the order already carries a different decision, which means an earlier
     * attempt got through. Treating that as success is what makes the outbox
     * safe to resend.
     */
    void sendDecision(String orderId, String decision, Long shopOrderId, String reason) {
        // Tiangge requires shopOrderId on every decision, and refuses the call
        // without it. A backorder has no local order yet, so it is identified by
        // a placeholder derived from the marketplace order id: stable across
        // every retry (which keeps the decision idempotent), far under the
        // 60-character limit, and recognisable in Tiangge's logs. The real local
        // order id replaces it in the link row once the goods arrive.
        String reference = shopOrderId == null ? "SO-PENDING-" + orderId : "SO-" + shopOrderId;
        TianggeBodies.Decision body = new TianggeBodies.Decision(decision, reference, reason);
        try {
            call("POST /orders/" + orderId + "/decision",
                    http.post().uri("/orders/{orderId}/decision", orderId).body(body),
                    ResponseSpec::toBodilessEntity);
        } catch (TianggeCallFailed e) {
            if (e.isConflict() && "decision_conflict".equals(e.code())) {
                log.info("Decision {} for order {} was already recorded upstream (decision_conflict)",
                        decision, orderId);
                return;
            }
            throw e;
        }
    }

    /**
     * POST /orders/{orderId}/resolution - how a backorder ended. Only valid
     * while Tiangge still considers the order a backorder; a 409
     * {@code not_backordered} therefore just means someone already settled it.
     */
    void sendResolution(String orderId, String status) {
        TianggeBodies.Resolution body = new TianggeBodies.Resolution(status);
        try {
            call("POST /orders/" + orderId + "/resolution",
                    http.post().uri("/orders/{orderId}/resolution", orderId).body(body),
                    ResponseSpec::toBodilessEntity);
        } catch (TianggeCallFailed e) {
            if (e.isConflict() && "not_backordered".equals(e.code())) {
                log.info("Order {} is no longer a backorder upstream; resolution {} ignored", orderId, status);
                return;
            }
            throw e;
        }
    }

    /**
     * POST /orders/{orderId}/cancellation - confirms the reserved units went
     * back to stock. A 409 {@code not_cancelled} means the customer never
     * cancelled, so there is nothing to confirm; logged, not retried.
     */
    void sendCancellation(String orderId) {
        TianggeBodies.Cancellation body = new TianggeBodies.Cancellation(true);
        try {
            call("POST /orders/" + orderId + "/cancellation",
                    http.post().uri("/orders/{orderId}/cancellation", orderId).body(body),
                    ResponseSpec::toBodilessEntity);
        } catch (TianggeCallFailed e) {
            if (e.isConflict() && "not_cancelled".equals(e.code())) {
                log.warn("Tiangge does not consider order {} cancelled; nothing to confirm", orderId);
                return;
            }
            throw e;
        }
    }

    /** Milliseconds since an instant; -1 when the timestamp was absent. */
    static long ageMillis(Instant then) {
        return then == null ? -1 : Duration.between(then, Instant.now()).toMillis();
    }

    /**
     * Sends one request and reads the response through Spring's own message
     * converter, turning anything that is not a 2xx into a
     * {@link TianggeCallFailed}.
     *
     * The status check is an {@code onStatus} handler rather than
     * {@code retrieve().onStatus(...)}'s default behaviour because the code
     * inside the error body is what decides whether a 409 is benign.
     *
     * The spec is handed to {@link Retries} and can therefore be executed more
     * than once: RestClient re-writes the headers and body on each call.
     */
    private <T> T call(String what, RequestHeadersSpec<?> spec, Function<ResponseSpec, T> reader) {
        return Retries.call(what, () -> {
            try {
                return reader.apply(spec.retrieve()
                        .onStatus(HttpStatusCode::isError, (request, response) -> {
                            throw failure(what, response.getStatusCode().value(), readBody(response));
                        }));
            } catch (TianggeCallFailed e) {
                throw e;
            } catch (Exception e) {
                throw new TianggeCallFailed(what + ": " + e, 0, "transport_error", true);
            }
        });
    }

    private TianggeCallFailed failure(String what, int status, String raw) {
        String code = errorCode(raw);
        return new TianggeCallFailed(
                what + ": HTTP " + status + " " + code + " - " + errorMessage(raw),
                status, code, status >= 500);
    }

    /**
     * The error code, and the human message beside it.
     *
     * The message is kept because Tiangge names the offending field in it, and
     * without it {@code invalid_request} says nothing actionable. A body with no
     * message field is shown as-is so an unexpected shape is still visible.
     *
     * A pattern is used rather than a JSON parse on purpose: this runs on the
     * failure path, where the body may be empty, truncated or not JSON at all,
     * and a missing code must never turn into a second failure.
     */
    private static String errorMessage(String raw) {
        String message = firstGroup(ERROR_MESSAGE, raw);
        if (message != null && !message.isBlank()) {
            return message;
        }
        if (raw == null || raw.isBlank()) {
            return "no response body";
        }
        String trimmed = raw.strip();
        return trimmed.length() > 200 ? trimmed.substring(0, 200) + "..." : trimmed;
    }

    private static String errorCode(String raw) {
        String code = firstGroup(ERROR_CODE, raw);
        return code == null ? "" : code;
    }

    private static String firstGroup(Pattern pattern, String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        Matcher matcher = pattern.matcher(raw);
        if (!matcher.find()) {
            return null;
        }
        return matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
    }

    private String readBody(ClientHttpResponse response) {
        try (InputStream in = response.getBody()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }
}