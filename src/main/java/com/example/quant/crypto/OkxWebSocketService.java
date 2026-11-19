package com.example.quant.crypto;

import com.example.quant.account.OkxAccountBindingService;
import com.example.quant.auth.AuthUserContext;
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
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class OkxWebSocketService {
    private static final URI DEMO_PRIVATE_URL = URI.create("wss://wspap.okx.com:8443/ws/v5/private");
    private static final long MAX_RECONNECT_DELAY_MS = 30_000L;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(6);

    private final TradingMode tradingMode;
    private final OkxProperties properties;
    private final OkxAccountBindingService binding;
    private final OkxSigner signer;
    private final ObjectMapper objectMapper;
    private final OkxOrderEventService orderEvents;
    private final OkxRuntimeReconciliationService reconciliation;
    private final SystemControlService systemControl;
    private final TradingReadinessService readiness;
    private final Connector connector;
    private final Clock clock;
    private volatile OkxPrivateWebSocketState connectionState = OkxPrivateWebSocketState.DISCONNECTED;
    private volatile String reason = "NOT_CONNECTED";
    private volatile WebSocket socket;
    private volatile OkxPrivateWebSocketProtocol protocol;
    private volatile Instant nextReconnectAt = Instant.EPOCH;
    private volatile Instant lastPingAt = Instant.EPOCH;
    private volatile Instant lastReconciliationAt = Instant.EPOCH;
    private volatile boolean reconciliationRequested;
    private int reconnectAttempt;

    public OkxWebSocketService() {
        this.tradingMode = TradingMode.DISABLED;
        this.properties = null;
        this.binding = null;
        this.signer = null;
        this.objectMapper = null;
        this.orderEvents = null;
        this.reconciliation = null;
        this.systemControl = null;
        this.readiness = null;
        this.connector = null;
        this.clock = Clock.systemUTC();
    }

    @Autowired
    public OkxWebSocketService(TradingProperties tradingProperties, OkxProperties properties,
                               OkxAccountBindingService binding, OkxSigner signer, ObjectMapper objectMapper,
                               OkxOrderEventService orderEvents, OkxRuntimeReconciliationService reconciliation,
                               SystemControlService systemControl, TradingReadinessService readiness) {
        this(tradingProperties, properties, binding, signer, objectMapper, orderEvents, reconciliation,
                systemControl, readiness,
                (uri, listener) -> HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build()
                        .newWebSocketBuilder().connectTimeout(CONNECT_TIMEOUT).buildAsync(uri, listener),
                Clock.systemUTC());
    }

    OkxWebSocketService(TradingProperties tradingProperties, OkxProperties properties,
                        OkxAccountBindingService binding, OkxSigner signer, ObjectMapper objectMapper,
                        OkxOrderEventService orderEvents, OkxRuntimeReconciliationService reconciliation,
                        SystemControlService systemControl, TradingReadinessService readiness,
                        Connector connector, Clock clock) {
        this.tradingMode = tradingProperties == null ? TradingMode.DISABLED : tradingProperties.mode();
        this.properties = properties;
        this.binding = binding;
        this.signer = signer;
        this.objectMapper = objectMapper;
        this.orderEvents = orderEvents;
        this.reconciliation = reconciliation;
        this.systemControl = systemControl;
        this.readiness = readiness;
        this.connector = connector;
        this.clock = clock;
    }

    public String status() {
        return privateStatus();
    }

    public String publicStatus() {
        return "NOT_IMPLEMENTED";
    }

    public String privateStatus() {
        OkxPrivateWebSocketProtocol current = protocol;
        return current == null ? connectionState.name() : current.state().name();
    }

    public String privateReason() {
        OkxPrivateWebSocketProtocol current = protocol;
        return current == null ? reason : current.reason();
    }

    @Scheduled(fixedDelay = 1000L, initialDelay = 5000L)
    void maintain() {
        if (!tradingMode.writesEnabled()) {
            connectionState = OkxPrivateWebSocketState.DISCONNECTED;
            reason = "TRADING_MODE_DISABLED";
            return;
        }
        Instant now = clock.instant();
        OkxPrivateWebSocketProtocol current = protocol;
        if (socket != null && current != null) {
            if (current.state() == OkxPrivateWebSocketState.FAILED
                    || current.state() == OkxPrivateWebSocketState.DEGRADED) {
                socket.abort();
                onDisconnected(current.reason());
                return;
            }
            maintainHeartbeat(current, now);
            if (current.state() == OkxPrivateWebSocketState.READY
                    && (reconciliationRequested || Duration.between(lastReconciliationAt, now).toSeconds() >= 30)) {
                reconciliationRequested = false;
                reconcile(owner());
            }
            return;
        }
        if (now.isBefore(nextReconnectAt)) {
            return;
        }
        connect(now);
    }

    private synchronized void connect(Instant now) {
        if (socket != null || connector == null) {
            return;
        }
        String owner = owner();
        Optional<OkxAccountBindingService.OkxCredentials> credentials = AuthUserContext.callAs(owner, binding::credentials);
        if (credentials.isEmpty()) {
            connectionState = OkxPrivateWebSocketState.DISCONNECTED;
            reason = "OKX_ACCOUNT_UNBOUND";
            nextReconnectAt = now.plusSeconds(30);
            return;
        }
        boolean reconnecting = reconnectAttempt > 0;
        connectionState = reconnecting ? OkxPrivateWebSocketState.RECONNECTING : OkxPrivateWebSocketState.CONNECTING;
        reason = reconnecting ? "RECONNECTING" : "CONNECTING";
        OkxPrivateWebSocketProtocol nextProtocol = new OkxPrivateWebSocketProtocol(
                objectMapper, signer, orderEvents, () -> reconcile(owner), clock, owner,
                () -> reconciliationRequested = true);
        protocol = nextProtocol;
        nextProtocol.markConnecting(reconnecting);
        Listener listener = new Listener(nextProtocol, credentials.get());
        connector.connect(privateUri(), listener).whenComplete((connected, error) -> {
            if (error != null) {
                nextProtocol.fail("WS_CONNECT_FAILED");
                onDisconnected("WS_CONNECT_FAILED");
            }
        });
    }

    private boolean reconcile(String owner) {
        OkxRuntimeReconciliationService.Result result = reconciliation.reconcile(owner);
        lastReconciliationAt = clock.instant();
        if (result.ready()) {
            reconnectAttempt = 0;
            readiness.updateConnection(TradingEngineState.READY, "PRIVATE_WS_RECONCILED");
            return true;
        }
        readiness.updateConnection(TradingEngineState.BLOCKED, result.reason());
        return false;
    }

    private void maintainHeartbeat(OkxPrivateWebSocketProtocol current, Instant now) {
        Instant lastMessage = current.lastMessageAt();
        long heartbeatMs = Math.max(1_000L, properties.websocket().heartbeatIntervalMs());
        if (lastMessage != null && Duration.between(lastMessage, now).toMillis() > heartbeatMs * 2) {
            socket.abort();
            onDisconnected("PRIVATE_WS_STALE");
            return;
        }
        if (Duration.between(lastPingAt, now).toMillis() >= heartbeatMs) {
            current.ping();
            lastPingAt = now;
        }
    }

    private synchronized void onDisconnected(String disconnectReason) {
        socket = null;
        OkxPrivateWebSocketProtocol current = protocol;
        if (current != null) {
            current.disconnected(disconnectReason);
        }
        connectionState = OkxPrivateWebSocketState.DEGRADED;
        reason = disconnectReason;
        readiness.updateConnection(TradingEngineState.DEGRADED, disconnectReason);
        reconnectAttempt++;
        long delay = reconnectDelayMillis(reconnectAttempt,
                Math.max(1_000L, properties.websocket().reconnectDelayMs()));
        nextReconnectAt = clock.instant().plusMillis(delay);
    }

    private URI privateUri() {
        if (tradingMode == TradingMode.DEMO) {
            return DEMO_PRIVATE_URL;
        }
        return URI.create(properties.websocket().privateUrl());
    }

    private String owner() {
        String owner = systemControl.autoTradeOwnerUsername();
        return owner == null || owner.isBlank() ? "local-admin" : owner;
    }

    static long reconnectDelayMillis(int attempt, long baseDelayMs) {
        int shift = Math.min(Math.max(0, attempt - 1), 5);
        long exponential = Math.min(MAX_RECONNECT_DELAY_MS, baseDelayMs << shift);
        long jitterBound = Math.max(1L, exponential / 4L);
        return Math.min(MAX_RECONNECT_DELAY_MS, exponential + ThreadLocalRandom.current().nextLong(jitterBound));
    }

    @FunctionalInterface
    interface Connector {
        CompletableFuture<WebSocket> connect(URI uri, WebSocket.Listener listener);
    }

    private final class Listener implements WebSocket.Listener {
        private final StringBuilder fragments = new StringBuilder();
        private final OkxPrivateWebSocketProtocol session;
        private OkxAccountBindingService.OkxCredentials credentials;

        private Listener(OkxPrivateWebSocketProtocol session,
                         OkxAccountBindingService.OkxCredentials credentials) {
            this.session = session;
            this.credentials = credentials;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            socket = webSocket;
            lastPingAt = clock.instant();
            webSocket.request(1);
            session.open(credentials, text -> webSocket.sendText(text, true).whenComplete((ignored, error) -> {
                if (error != null) {
                    session.fail("WS_SEND_FAILED");
                }
            }));
            credentials = null;
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            fragments.append(data);
            if (last) {
                String message = fragments.toString();
                fragments.setLength(0);
                session.receive(message);
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String closeReason) {
            onDisconnected("PRIVATE_WS_DISCONNECTED:" + statusCode);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            onDisconnected("PRIVATE_WS_ERROR");
        }
    }
}
