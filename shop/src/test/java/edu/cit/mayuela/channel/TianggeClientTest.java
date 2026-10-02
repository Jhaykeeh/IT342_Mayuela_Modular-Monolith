package edu.cit.mayuela.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Wire-shape tests for {@link TianggeClient}.
 *
 * <p>Both cases here were found the expensive way, against the live marketplace:
 * a decision sent without a {@code shopOrderId} was rejected for forty minutes
 * with an error message that named no field, because only the code was kept.
 * Neither bug needs a live server to catch, so they are pinned here.
 */
class TianggeClientTest {

    private MockRestServiceServer server;

    private TianggeClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://tiangge.test");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new TianggeClient(builder.build());
    }

    @Test
    @DisplayName("a backorder decision still carries a shopOrderId")
    void backorderDecisionCarriesAPlaceholder() {
        AtomicReference<String> body = expectDecision("TG-9QUX4E");

        client.sendDecision("TG-9QUX4E", "BACKORDERED", null, "Awaiting supplier delivery");

        server.verify();
        // Tiangge rejects the call outright without this field, even though no
        // local order exists for a backorder yet.
        assertThat(body.get()).contains("\"shopOrderId\":\"SO-PENDING-TG-9QUX4E\"");
        assertThat(body.get()).contains("\"decision\":\"BACKORDERED\"");
    }

    @Test
    @DisplayName("the placeholder is identical on every retry, so a resend cannot conflict")
    void backorderPlaceholderIsStable() {
        AtomicReference<String> first = new AtomicReference<>();
        AtomicReference<String> second = new AtomicReference<>();
        server.expect(requestTo("http://tiangge.test/orders/TG-9QUX4E/decision"))
                .andExpect(request -> first.set(bodyOf(request)))
                .andRespond(withSuccess());
        server.expect(requestTo("http://tiangge.test/orders/TG-9QUX4E/decision"))
                .andExpect(request -> second.set(bodyOf(request)))
                .andRespond(withSuccess());

        client.sendDecision("TG-9QUX4E", "BACKORDERED", null, null);
        client.sendDecision("TG-9QUX4E", "BACKORDERED", null, null);

        server.verify();
        // Re-deciding with a different shopOrderId is what triggers
        // decision_conflict, so the placeholder must be derived, not random.
        assertThat(second.get()).isEqualTo(first.get());
    }

    @Test
    @DisplayName("a fulfilled order sends its local id")
    void acceptedDecisionSendsTheLocalOrderId() {
        AtomicReference<String> body = expectDecision("TG-XTVNJH");

        client.sendDecision("TG-XTVNJH", "ACCEPTED", 159L, null);

        server.verify();
        assertThat(body.get()).contains("\"shopOrderId\":\"SO-159\"");
        assertThat(body.get()).doesNotContain("SO-PENDING");
    }

    @Test
    @DisplayName("Tiangge's own message survives, naming the offending field")
    void errorMessageIsPreserved() {
        server.expect(requestTo("http://tiangge.test/orders/TG-1/decision"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"invalid_request\","
                                + "\"message\":\"shopOrderId is required: your own order ID, "
                                + "up to 60 characters.\"}"));

        assertThatThrownBy(() -> client.sendDecision("TG-1", "ACCEPTED", null, null))
                .isInstanceOf(TianggeCallFailed.class)
                // Without the message, "invalid_request" is unactionable.
                .hasMessageContaining("invalid_request")
                .hasMessageContaining("shopOrderId is required");
    }

    @Test
    @DisplayName("a client error is not retried, because it will fail the same way forever")
    void clientErrorsAreNotRetryable() {
        server.expect(requestTo("http://tiangge.test/orders/TG-1/decision"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"invalid_request\",\"message\":\"bad field\"}"));

        assertThatThrownBy(() -> client.sendDecision("TG-1", "ACCEPTED", 1L, null))
                .isInstanceOf(TianggeCallFailed.class)
                .matches(e -> !((TianggeCallFailed) e).isRetryable(), "4xx must not be retried");

        // verify() would fail on a second, undeclared request.
        server.verify();
    }

    @Test
    @DisplayName("a server error is retried, because it may be transient")
    void serverErrorsAreRetryable() {
        // Five expectations: Retries makes MAX_ATTEMPTS calls before giving up,
        // so a persistently failing endpoint is asked five times, not once.
        for (int attempt = 1; attempt <= 5; attempt++) {
            server.expect(requestTo("http://tiangge.test/orders/TG-1/decision"))
                    .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body("{\"error\":\"upstream_busy\",\"message\":\"try later\"}"));
        }

        assertThatThrownBy(() -> client.sendDecision("TG-1", "ACCEPTED", 1L, null))
                .isInstanceOf(TianggeCallFailed.class)
                .matches(e -> ((TianggeCallFailed) e).isRetryable(), "5xx must be retried");
        server.verify();
    }

    @Test
    @DisplayName("a decision already recorded upstream counts as sent")
    void decisionConflictIsSwallowed() {
        server.expect(requestTo("http://tiangge.test/orders/TG-1/decision"))
                .andRespond(withStatus(HttpStatus.CONFLICT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"decision_conflict\","
                                + "\"message\":\"order already decided as REJECTED\"}"));

        // The outbox resends until it succeeds; a 409 means an earlier attempt
        // landed, so throwing here would retry forever against a settled order.
        assertThatCode(() -> client.sendDecision("TG-1", "ACCEPTED", 1L, null))
                .doesNotThrowAnyException();
        server.verify();
    }

    private AtomicReference<String> expectDecision(String orderId) {
        AtomicReference<String> body = new AtomicReference<>();
        server.expect(requestTo("http://tiangge.test/orders/" + orderId + "/decision"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> body.set(bodyOf(request)))
                .andRespond(withSuccess());
        return body;
    }

    /**
     * The mock request factory buffers the outgoing body in memory, so it can be
     * read back to assert on what would really have gone over the wire.
     */
    private static String bodyOf(ClientHttpRequest request) throws IOException {
        return ((ByteArrayOutputStream) request.getBody()).toString(StandardCharsets.UTF_8);
    }
}
