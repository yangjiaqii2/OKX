package com.example.quant.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

import com.example.quant.account.OkxAccountBindingService;
import com.example.quant.account.OkxCredentialCodec;
import com.example.quant.account.OkxCredentialStore;
import com.example.quant.account.StoredOkxCredential;
import com.example.quant.config.OkxProperties;
import com.example.quant.config.TradingMode;
import com.example.quant.config.TradingProperties;
import com.example.quant.okxtrade.OkxOrderEventService;
import com.example.quant.system.OkxRuntimeReconciliationService;
import com.example.quant.system.SystemControlService;
import com.example.quant.system.TradingEngineState;
import com.example.quant.system.TradingReadinessService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.WebSocket;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class OkxWebSocketServiceTest {
    @Test
    void demoModeConnectsOnlyToOfficialDemoPrivateEndpoint() {
        AtomicReference<URI> uri = new AtomicReference<>();
        AtomicReference<WebSocket.Listener> listener = new AtomicReference<>();
        WebSocket socket = socket();
        OkxWebSocketService.Connector connector = (target, nextListener) -> {
            uri.set(target);
            listener.set(nextListener);
            nextListener.onOpen(socket);
            return CompletableFuture.completedFuture(socket);
        };
        OkxWebSocketService service = service(connector, new TradingReadinessService());

        service.maintain();

        assertThat(uri.get()).isEqualTo(URI.create("wss://wspap.okx.com:8443/ws/v5/private"));
        assertThat(service.privateStatus()).isEqualTo("AUTHENTICATING");
        assertThat(listener.get()).isNotNull();
        var order = inOrder(socket);
        order.verify(socket).request(1);
        order.verify(socket).sendText(any(), anyBoolean());
    }

    @Test
    void disconnectImmediatelyDegradesTradingAndDoesNotHotLoopReconnect() {
        AtomicInteger connects = new AtomicInteger();
        AtomicReference<WebSocket.Listener> listener = new AtomicReference<>();
        WebSocket socket = socket();
        TradingReadinessService readiness = new TradingReadinessService();
        readiness.update(TradingEngineState.READY, "TEST_READY");
        OkxWebSocketService service = service((target, nextListener) -> {
            connects.incrementAndGet();
            listener.set(nextListener);
            nextListener.onOpen(socket);
            return CompletableFuture.completedFuture(socket);
        }, readiness);
        service.maintain();

        listener.get().onClose(socket, 1006, "network reset");
        service.maintain();

        assertThat(service.privateStatus()).isEqualTo("DEGRADED");
        assertThat(readiness.snapshot().state()).isEqualTo(TradingEngineState.DEGRADED);
        assertThat(connects.get()).isEqualTo(1);
    }

    @Test
    void disabledModeNeverConnects() {
        AtomicInteger connects = new AtomicInteger();
        OkxWebSocketService service = new OkxWebSocketService(
                new TradingProperties(TradingMode.DISABLED, true, 120, false),
                properties(), binding(), new OkxSigner(), new ObjectMapper(), mock(OkxOrderEventService.class),
                mock(OkxRuntimeReconciliationService.class),
                new SystemControlService(new TradingProperties(TradingMode.DISABLED, true, 120, false)),
                new TradingReadinessService(), (target, listener) -> {
                    connects.incrementAndGet();
                    return CompletableFuture.completedFuture(socket());
                }, fixedClock());

        service.maintain();

        assertThat(connects.get()).isZero();
        assertThat(service.privateStatus()).isEqualTo("DISCONNECTED");
    }

    @Test
    void reconnectsThenReauthenticatesResubscribesAndReconcilesBeforeReady() {
        AtomicInteger connects = new AtomicInteger();
        AtomicReference<WebSocket.Listener> listener = new AtomicReference<>();
        WebSocket socket = socket();
        MutableClock clock = new MutableClock(Instant.parse("2026-08-09T12:00:00Z"));
        TradingReadinessService readiness = new TradingReadinessService();
        OkxWebSocketService service = service((target, nextListener) -> {
            connects.incrementAndGet();
            listener.set(nextListener);
            nextListener.onOpen(socket);
            return CompletableFuture.completedFuture(socket);
        }, readiness, clock);

        service.maintain();
        ready(listener.get(), socket);
        assertThat(service.privateStatus()).isEqualTo("READY");

        listener.get().onClose(socket, 1006, "network reset");
        clock.advanceSeconds(31);
        service.maintain();
        ready(listener.get(), socket);

        assertThat(connects.get()).isEqualTo(2);
        assertThat(service.privateStatus()).isEqualTo("READY");
        assertThat(readiness.snapshot().state()).isEqualTo(TradingEngineState.READY);
    }

    @Test
    void preservesProtocolFailureReasonWhenReconciliationFails() {
        AtomicReference<WebSocket.Listener> listener = new AtomicReference<>();
        WebSocket socket = socket();
        OkxWebSocketService service = service((target, nextListener) -> {
            listener.set(nextListener);
            nextListener.onOpen(socket);
            return CompletableFuture.completedFuture(socket);
        }, new TradingReadinessService(), fixedClock(), false);

        service.maintain();
        ready(listener.get(), socket);
        service.maintain();

        assertThat(service.privateReason()).isEqualTo("REST_RECONCILIATION_FAILED");
    }

    private static OkxWebSocketService service(OkxWebSocketService.Connector connector,
                                               TradingReadinessService readiness) {
        return service(connector, readiness, fixedClock());
    }

    private static OkxWebSocketService service(OkxWebSocketService.Connector connector,
                                               TradingReadinessService readiness, Clock clock) {
        return service(connector, readiness, clock, true);
    }

    private static OkxWebSocketService service(OkxWebSocketService.Connector connector,
                                               TradingReadinessService readiness, Clock clock,
                                               boolean reconciliationReady) {
        OkxRuntimeReconciliationService reconciliation = mock(OkxRuntimeReconciliationService.class);
        when(reconciliation.reconcile(any())).thenReturn(
                new OkxRuntimeReconciliationService.Result(reconciliationReady,
                        reconciliationReady ? "EXCHANGE_RECONCILED" : "TEST_RECONCILIATION_FAILED", 0));
        TradingProperties trading = new TradingProperties(TradingMode.DEMO, true, 120, false);
        return new OkxWebSocketService(trading, properties(), binding(), new OkxSigner(), new ObjectMapper(),
                mock(OkxOrderEventService.class), reconciliation, new SystemControlService(trading), readiness,
                connector, clock);
    }

    private static void ready(WebSocket.Listener listener, WebSocket socket) {
        listener.onText(socket, "{\"event\":\"login\",\"code\":\"0\"}", true);
        listener.onText(socket, "{\"event\":\"subscribe\",\"arg\":{\"channel\":\"orders\"}}", true);
        listener.onText(socket, "{\"event\":\"subscribe\",\"arg\":{\"channel\":\"positions\"}}", true);
        listener.onText(socket, "{\"event\":\"subscribe\",\"arg\":{\"channel\":\"account\"}}", true);
    }

    private static OkxProperties properties() {
        return new OkxProperties(new OkxProperties.Api("https://www.okx.com", "", "", ""),
                new OkxProperties.Websocket("wss://ws.okx.com:8443/ws/v5/public",
                        "wss://ws.okx.com:8443/ws/v5/private", 3000, 15000, 5000));
    }

    private static OkxAccountBindingService binding() {
        OkxCredentialStore store = new OkxCredentialStore() {
            @Override
            public Optional<StoredOkxCredential> findActive(String username) {
                return Optional.of(new StoredOkxCredential("key", "secret", "passphrase", "key"));
            }
            @Override public void saveActive(String username, StoredOkxCredential credential) { }
            @Override public void deleteActive(String username) { }
        };
        OkxCredentialCodec codec = new OkxCredentialCodec() {
            @Override public String encode(String value) { return value; }
            @Override public String decode(String value) { return value; }
        };
        return new OkxAccountBindingService(
                new TradingProperties(TradingMode.DEMO, true, 120, false), store, codec);
    }

    private static WebSocket socket() {
        WebSocket socket = mock(WebSocket.class);
        when(socket.sendText(any(), anyBoolean())).thenReturn(CompletableFuture.completedFuture(socket));
        when(socket.sendClose(any(Integer.class), any())).thenReturn(CompletableFuture.completedFuture(socket));
        return socket;
    }

    private static Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-08-09T12:00:00Z"), ZoneOffset.UTC);
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advanceSeconds(long seconds) {
            instant = instant.plusSeconds(seconds);
        }

        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}
