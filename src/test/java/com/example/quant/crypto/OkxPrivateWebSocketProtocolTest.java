package com.example.quant.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.example.quant.account.OkxAccountBindingService;
import com.example.quant.auth.AuthUserContext;
import com.example.quant.okxtrade.OkxOrderEventService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class OkxPrivateWebSocketProtocolTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void authenticatesSubscribesRequiredChannelsAndBecomesReadyOnlyAfterReconciliation() throws Exception {
        List<String> sent = new ArrayList<>();
        AtomicInteger reconciliations = new AtomicInteger();
        OkxPrivateWebSocketProtocol protocol = protocol(() -> {
            reconciliations.incrementAndGet();
            return true;
        });

        protocol.open(credentials(), sent::add);

        JsonNode login = JSON.readTree(sent.get(0));
        assertThat(protocol.state()).isEqualTo(OkxPrivateWebSocketState.AUTHENTICATING);
        assertThat(login.path("op").asText()).isEqualTo("login");
        assertThat(login.path("args").get(0).path("timestamp").asText()).isEqualTo("1700000000");
        assertThat(login.path("args").get(0).path("sign").asText()).isEqualTo(
                new OkxSigner().sign("1700000000", "GET", "/users/self/verify", "", "secret"));

        protocol.receive("{\"event\":\"login\",\"code\":\"0\"}");
        JsonNode subscribe = JSON.readTree(sent.get(1));
        assertThat(protocol.state()).isEqualTo(OkxPrivateWebSocketState.SUBSCRIBING);
        assertThat(subscribe.path("id").asText()).matches("[A-Za-z0-9]{1,32}");
        assertThat(subscribe.path("args")).hasSize(3);
        assertThat(subscribe.path("args")).extracting(node -> node.path("channel").asText())
                .containsExactly("orders", "positions", "account");

        protocol.receive("{\"event\":\"subscribe\",\"arg\":{\"channel\":\"orders\"}}");
        protocol.receive("{\"event\":\"subscribe\",\"arg\":{\"channel\":\"positions\"}}");
        assertThat(protocol.state()).isEqualTo(OkxPrivateWebSocketState.SUBSCRIBING);
        protocol.receive("{\"event\":\"subscribe\",\"arg\":{\"channel\":\"account\"}}");

        assertThat(protocol.state()).isEqualTo(OkxPrivateWebSocketState.READY);
        assertThat(reconciliations.get()).isEqualTo(1);
    }

    @Test
    void failedAuthenticationNeverBecomesReady() {
        OkxPrivateWebSocketProtocol protocol = protocol(() -> true);
        protocol.open(credentials(), ignored -> { });

        protocol.receive("{\"event\":\"login\",\"code\":\"60009\",\"msg\":\"Login failed\"}");

        assertThat(protocol.state()).isEqualTo(OkxPrivateWebSocketState.FAILED);
        assertThat(protocol.reason()).contains("WS_LOGIN_FAILED");
    }

    @Test
    void protocolErrorPreservesExchangeMessage() {
        OkxPrivateWebSocketProtocol protocol = protocol(() -> true);
        protocol.open(credentials(), ignored -> { });

        protocol.receive("{\"event\":\"error\",\"code\":\"60033\",\"msg\":\"test exchange reason\"}");

        assertThat(protocol.reason()).isEqualTo("WS_ERROR:60033:test exchange reason");
    }

    @Test
    void failedRestReconciliationLeavesSessionDegraded() {
        OkxPrivateWebSocketProtocol protocol = protocol(() -> false);
        protocol.open(credentials(), ignored -> { });
        protocol.receive("{\"event\":\"login\",\"code\":\"0\"}");
        protocol.receive("{\"event\":\"subscribe\",\"arg\":{\"channel\":\"orders\"}}");
        protocol.receive("{\"event\":\"subscribe\",\"arg\":{\"channel\":\"positions\"}}");
        protocol.receive("{\"event\":\"subscribe\",\"arg\":{\"channel\":\"account\"}}");

        assertThat(protocol.state()).isEqualTo(OkxPrivateWebSocketState.DEGRADED);
        assertThat(protocol.reason()).isEqualTo("REST_RECONCILIATION_FAILED");
    }

    @Test
    void routesOrderEventsUnderBoundOwnerAndDegradesOnMalformedJson() {
        OkxOrderEventService orderEvents = mock(OkxOrderEventService.class);
        AtomicReference<String> callbackOwner = new AtomicReference<>();
        org.mockito.Mockito.when(orderEvents.apply(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            callbackOwner.set(AuthUserContext.currentUsername().orElse(null));
            return true;
        });
        OkxPrivateWebSocketProtocol protocol = new OkxPrivateWebSocketProtocol(
                JSON, new OkxSigner(), orderEvents, () -> true,
                Clock.fixed(Instant.ofEpochSecond(1_700_000_000L), ZoneOffset.UTC), "alice");
        protocol.open(credentials(), ignored -> { });
        protocol.receive("{\"arg\":{\"channel\":\"orders\"},\"data\":[{\"ordId\":\"ord-1\"}]}");

        verify(orderEvents, times(1)).apply(org.mockito.ArgumentMatchers.argThat(
                node -> "ord-1".equals(node.path("ordId").asText())));
        assertThat(callbackOwner.get()).isEqualTo("alice");
        assertThat(AuthUserContext.currentUsername()).isEmpty();

        protocol.receive("{bad-json");
        assertThat(protocol.state()).isEqualTo(OkxPrivateWebSocketState.DEGRADED);
        assertThat(protocol.reason()).isEqualTo("MALFORMED_WS_MESSAGE");
    }

    private static OkxPrivateWebSocketProtocol protocol(java.util.function.BooleanSupplier reconciliation) {
        return new OkxPrivateWebSocketProtocol(
                JSON,
                new OkxSigner(),
                mock(OkxOrderEventService.class),
                reconciliation,
                Clock.fixed(Instant.ofEpochSecond(1_700_000_000L), ZoneOffset.UTC),
                "alice"
        );
    }

    private static OkxAccountBindingService.OkxCredentials credentials() {
        return new OkxAccountBindingService.OkxCredentials("key", "secret", "passphrase");
    }
}
