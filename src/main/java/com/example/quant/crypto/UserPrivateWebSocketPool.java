package com.example.quant.crypto;

import com.example.quant.account.OkxAccountBindingService;
import com.example.quant.agent.platform.TradingAgentEntity;
import com.example.quant.agent.platform.TradingAgentRepository;
import com.example.quant.agent.platform.TradingAgentRunMode;
import com.example.quant.agent.platform.TradingAgentStatus;
import com.example.quant.auth.AuthUserContext;
import com.example.quant.config.OkxProperties;
import com.example.quant.config.TradingMode;
import com.example.quant.config.TradingProperties;
import com.example.quant.okxtrade.OkxOrderEventService;
import com.example.quant.system.OkxRuntimeReconciliationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Multi-user dynamic private WebSocket connection pool.
 * Maintains isolated private WS sessions only for users who have active non-PAPER trading agents.
 * A failure or credential revocation on one user's session never impacts any other user.
 */
@Service
public class UserPrivateWebSocketPool {
    private static final Logger log = LoggerFactory.getLogger(UserPrivateWebSocketPool.class);
    private static final URI DEMO_PRIVATE_URL = URI.create("wss://wspap.okx.com:8443/ws/v5/private");
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(6);

    private final TradingMode tradingMode;
    private final OkxProperties properties;
    private final OkxAccountBindingService binding;
    private final OkxSigner signer;
    private final ObjectMapper objectMapper;
    private final OkxOrderEventService orderEvents;
    private final OkxRuntimeReconciliationService reconciliation;
    private final TradingAgentRepository agentRepository;
    private final OkxWebSocketService.Connector connector;
    private final Clock clock;
    private final Map<String, UserSession> sessions = new ConcurrentHashMap<>();

    @Autowired
    public UserPrivateWebSocketPool(
            TradingProperties tradingProperties,
            OkxProperties properties,
            OkxAccountBindingService binding,
            OkxSigner signer,
            ObjectMapper objectMapper,
            OkxOrderEventService orderEvents,
            OkxRuntimeReconciliationService reconciliation,
            TradingAgentRepository agentRepository
    ) {
        this(
                tradingProperties,
                properties,
                binding,
                signer,
                objectMapper,
                orderEvents,
                reconciliation,
                agentRepository,
                (uri, listener) -> HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build()
                        .newWebSocketBuilder().connectTimeout(CONNECT_TIMEOUT).buildAsync(uri, listener),
                Clock.systemUTC()
        );
    }

    UserPrivateWebSocketPool(
            TradingProperties tradingProperties,
            OkxProperties properties,
            OkxAccountBindingService binding,
            OkxSigner signer,
            ObjectMapper objectMapper,
            OkxOrderEventService orderEvents,
            OkxRuntimeReconciliationService reconciliation,
            TradingAgentRepository agentRepository,
            OkxWebSocketService.Connector connector,
            Clock clock
    ) {
        this.tradingMode = tradingProperties == null ? TradingMode.DISABLED : tradingProperties.mode();
        this.properties = properties;
        this.binding = binding;
        this.signer = signer;
        this.objectMapper = objectMapper;
        this.orderEvents = orderEvents;
        this.reconciliation = reconciliation;
        this.agentRepository = agentRepository;
        this.connector = connector;
        this.clock = clock;
    }

    @Scheduled(fixedDelay = 2000L, initialDelay = 6000L)
    public void maintainPool() {
        if (!tradingMode.writesEnabled()) {
            disconnectAll("TRADING_MODE_DISABLED");
            return;
        }
        Set<String> desiredUsers = agentRepository.findByStatusOrderByUpdatedAtAsc(TradingAgentStatus.RUNNING).stream()
                .filter(agent -> agent.getRunMode() != TradingAgentRunMode.PAPER)
                .map(TradingAgentEntity::getUserName)
                .filter(u -> u != null && !u.isBlank())
                .collect(Collectors.toSet());

        // Disconnect sessions for users who no longer have active live/demo agents
        sessions.keySet().stream()
                .filter(user -> !desiredUsers.contains(user))
                .toList()
                .forEach(user -> removeSession(user, "NO_ACTIVE_NON_PAPER_AGENT"));

        Instant now = clock.instant();
        for (String username : desiredUsers) {
            UserSession session = sessions.computeIfAbsent(username, UserSession::new);
            try {
                maintainUser(session, now);
            } catch (Exception ex) {
                log.warn("Private WS pool maintenance failed for user={}: {}", username, ex.getMessage());
                session.onDisconnected("WS_MAINTAIN_ERROR", now, baseReconnectDelayMs());
            }
        }
    }

    public UserWsStatus statusForUser(String username) {
        if (username == null || username.isBlank()) {
            return new UserWsStatus("", OkxPrivateWebSocketState.DISCONNECTED.name(), "NO_USER", null);
        }
        UserSession session = sessions.get(username.trim());
        if (session == null) {
            return new UserWsStatus(
                    username.trim(),
                    OkxPrivateWebSocketState.DISCONNECTED.name(),
                    tradingMode.writesEnabled() ? "IDLE_NO_ACTIVE_AGENT" : "TRADING_MODE_DISABLED",
                    null
            );
        }
        return session.snapshot();
    }

    public List<UserWsStatus> poolSnapshot() {
        return sessions.values().stream()
                .map(UserSession::snapshot)
                .sorted(Comparator.comparing(UserWsStatus::username))
                .toList();
    }

    private void maintainUser(UserSession session, Instant now) {
        OkxPrivateWebSocketProtocol current = session.protocol;
        if (session.socket != null && current != null) {
            if (current.state() == OkxPrivateWebSocketState.FAILED
                    || current.state() == OkxPrivateWebSocketState.DEGRADED) {
                session.abortSocket();
                session.onDisconnected(current.reason(), now, baseReconnectDelayMs());
                return;
            }
            long heartbeatMs = properties == null ? 20_000L : Math.max(1_000L, properties.websocket().heartbeatIntervalMs());
            Instant lastMessage = current.lastMessageAt();
            if (lastMessage != null && Duration.between(lastMessage, now).toMillis() > heartbeatMs * 2) {
                session.abortSocket();
                session.onDisconnected("PRIVATE_WS_STALE", now, baseReconnectDelayMs());
                return;
            }
            if (Duration.between(session.lastPingAt, now).toMillis() >= heartbeatMs) {
                current.ping();
                session.lastPingAt = now;
            }
            if (current.state() == OkxPrivateWebSocketState.READY
                    && (session.reconciliationRequested || Duration.between(session.lastReconciliationAt, now).toSeconds() >= 30)) {
                session.reconciliationRequested = false;
                reconcileUser(session);
            }
            return;
        }
        if (now.isBefore(session.nextReconnectAt)) {
            return;
        }
        connectUser(session, now);
    }

    private void connectUser(UserSession session, Instant now) {
        if (connector == null || session.socket != null) {
            return;
        }
        Optional<OkxAccountBindingService.OkxCredentials> credentials =
                AuthUserContext.callAs(session.username, binding::credentials);
        if (credentials.isEmpty()) {
            session.state = OkxPrivateWebSocketState.DISCONNECTED;
            session.reason = "OKX_ACCOUNT_UNBOUND";
            session.nextReconnectAt = now.plusSeconds(30);
            return;
        }
        boolean reconnecting = session.reconnectAttempt > 0;
        session.state = reconnecting ? OkxPrivateWebSocketState.RECONNECTING : OkxPrivateWebSocketState.CONNECTING;
        session.reason = reconnecting ? "RECONNECTING" : "CONNECTING";
        OkxPrivateWebSocketProtocol nextProtocol = new OkxPrivateWebSocketProtocol(
                objectMapper, signer, orderEvents, () -> reconcileUser(session), clock, session.username,
                () -> session.reconciliationRequested = true
        );
        session.protocol = nextProtocol;
        nextProtocol.markConnecting(reconnecting);
        connector.connect(privateUri(), new PoolListener(session, nextProtocol, credentials.get()))
                .whenComplete((connected, error) -> {
                    if (error != null) {
                        nextProtocol.fail("WS_CONNECT_FAILED");
                        session.onDisconnected("WS_CONNECT_FAILED", clock.instant(), baseReconnectDelayMs());
                    }
                });
    }

    private boolean reconcileUser(UserSession session) {
        OkxRuntimeReconciliationService.Result result = reconciliation.reconcile(session.username);
        session.lastReconciliationAt = clock.instant();
        if (result.ready()) {
            session.reconnectAttempt = 0;
            return true;
        }
        session.reason = result.reason();
        return false;
    }

    private void disconnectAll(String reason) {
        for (String user : List.copyOf(sessions.keySet())) {
            removeSession(user, reason);
        }
    }

    private void removeSession(String username, String reason) {
        UserSession removed = sessions.remove(username);
        if (removed != null) {
            removed.abortSocket();
            removed.state = OkxPrivateWebSocketState.DISCONNECTED;
            removed.reason = reason;
        }
    }

    private URI privateUri() {
        if (tradingMode == TradingMode.DEMO) {
            return DEMO_PRIVATE_URL;
        }
        return URI.create(properties.websocket().privateUrl());
    }

    private long baseReconnectDelayMs() {
        return properties == null ? 3_000L : Math.max(1_000L, properties.websocket().reconnectDelayMs());
    }

    public record UserWsStatus(
            String username,
            String state,
            String reason,
            Instant lastReconciliationAt
    ) {
    }

    private static final class UserSession {
        final String username;
        volatile OkxPrivateWebSocketState state = OkxPrivateWebSocketState.DISCONNECTED;
        volatile String reason = "NOT_CONNECTED";
        volatile WebSocket socket;
        volatile OkxPrivateWebSocketProtocol protocol;
        volatile Instant nextReconnectAt = Instant.EPOCH;
        volatile Instant lastPingAt = Instant.EPOCH;
        volatile Instant lastReconciliationAt = Instant.EPOCH;
        volatile boolean reconciliationRequested;
        int reconnectAttempt;

        UserSession(String username) {
            this.username = username;
        }

        void abortSocket() {
            WebSocket s = socket;
            socket = null;
            if (s != null) {
                try {
                    s.abort();
                } catch (Exception ignored) {
                    // Best effort close.
                }
            }
        }

        void onDisconnected(String disconnectReason, Instant now, long baseDelayMs) {
            socket = null;
            OkxPrivateWebSocketProtocol current = protocol;
            if (current != null) {
                current.disconnected(disconnectReason);
            }
            state = OkxPrivateWebSocketState.DEGRADED;
            reason = disconnectReason;
            reconnectAttempt++;
            long delay = OkxWebSocketService.reconnectDelayMillis(reconnectAttempt, baseDelayMs);
            nextReconnectAt = now.plusMillis(delay);
        }

        UserWsStatus snapshot() {
            OkxPrivateWebSocketProtocol current = protocol;
            String effectiveState = current == null ? state.name() : current.state().name();
            String effectiveReason = current == null ? reason : current.reason();
            return new UserWsStatus(
                    username,
                    effectiveState,
                    effectiveReason,
                    Instant.EPOCH.equals(lastReconciliationAt) ? null : lastReconciliationAt
            );
        }
    }

    private final class PoolListener implements WebSocket.Listener {
        private final StringBuilder fragments = new StringBuilder();
        private final UserSession userSession;
        private final OkxPrivateWebSocketProtocol protocol;
        private final OkxAccountBindingService.OkxCredentials credentials;

        PoolListener(
                UserSession userSession,
                OkxPrivateWebSocketProtocol protocol,
                OkxAccountBindingService.OkxCredentials credentials
        ) {
            this.userSession = userSession;
            this.protocol = protocol;
            this.credentials = credentials;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            userSession.socket = webSocket;
            userSession.lastPingAt = clock.instant();
            webSocket.request(1);
            protocol.open(credentials, text -> webSocket.sendText(text, true).whenComplete((ignored, error) -> {
                if (error != null) {
                    protocol.fail("WS_SEND_FAILED");
                }
            }));
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            fragments.append(data);
            if (last) {
                String payload = fragments.toString();
                fragments.setLength(0);
                protocol.receive(payload);
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            userSession.onDisconnected("WS_CLOSED_" + statusCode, clock.instant(), baseReconnectDelayMs());
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            userSession.onDisconnected("WS_ERROR", clock.instant(), baseReconnectDelayMs());
        }
    }
}
