package com.example.quant.crypto;

import com.example.quant.account.OkxAccountBindingService;
import com.example.quant.auth.AuthUserContext;
import com.example.quant.okxtrade.OkxOrderEventService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

final class OkxPrivateWebSocketProtocol {
    private static final Set<String> REQUIRED_CHANNELS = Set.of("orders", "positions", "account");

    private final ObjectMapper objectMapper;
    private final OkxSigner signer;
    private final OkxOrderEventService orderEvents;
    private final BooleanSupplier reconciliation;
    private final Clock clock;
    private final String owner;
    private final Runnable accountOrPositionUpdate;
    private final Set<String> subscribed = new HashSet<>();
    private volatile OkxPrivateWebSocketState state = OkxPrivateWebSocketState.DISCONNECTED;
    private volatile String reason = "NOT_CONNECTED";
    private volatile Instant lastMessageAt;
    private Consumer<String> sender;

    OkxPrivateWebSocketProtocol(ObjectMapper objectMapper, OkxSigner signer,
                                OkxOrderEventService orderEvents, BooleanSupplier reconciliation,
                                Clock clock, String owner) {
        this(objectMapper, signer, orderEvents, reconciliation, clock, owner, () -> { });
    }

    OkxPrivateWebSocketProtocol(ObjectMapper objectMapper, OkxSigner signer,
                                OkxOrderEventService orderEvents, BooleanSupplier reconciliation,
                                Clock clock, String owner, Runnable accountOrPositionUpdate) {
        this.objectMapper = objectMapper;
        this.signer = signer;
        this.orderEvents = orderEvents;
        this.reconciliation = reconciliation;
        this.clock = clock;
        this.owner = owner;
        this.accountOrPositionUpdate = accountOrPositionUpdate;
    }

    synchronized void open(OkxAccountBindingService.OkxCredentials credentials, Consumer<String> nextSender) {
        sender = nextSender;
        subscribed.clear();
        lastMessageAt = clock.instant();
        state = OkxPrivateWebSocketState.AUTHENTICATING;
        reason = "WS_LOGIN_PENDING";
        String timestamp = Long.toString(clock.instant().getEpochSecond());
        ObjectNode login = objectMapper.createObjectNode();
        login.put("op", "login");
        ObjectNode arg = login.putArray("args").addObject();
        arg.put("apiKey", credentials.apiKey());
        arg.put("passphrase", credentials.passphrase());
        arg.put("timestamp", timestamp);
        arg.put("sign", signer.sign(timestamp, "GET", "/users/self/verify", "", credentials.secret()));
        send(login);
    }

    synchronized void receive(String message) {
        lastMessageAt = clock.instant();
        if ("pong".equals(message)) {
            return;
        }
        final JsonNode root;
        try {
            root = objectMapper.readTree(message);
        } catch (Exception ex) {
            state = OkxPrivateWebSocketState.DEGRADED;
            reason = "MALFORMED_WS_MESSAGE";
            return;
        }
        String event = root.path("event").asText("");
        if ("error".equals(event)) {
            state = OkxPrivateWebSocketState.FAILED;
            String exchangeMessage = root.path("msg").asText("");
            reason = "WS_ERROR:" + compact(root.path("code").asText("UNKNOWN"))
                    + (exchangeMessage.isBlank() ? "" : ":" + compact(exchangeMessage));
            return;
        }
        if ("login".equals(event)) {
            onLogin(root);
            return;
        }
        if ("subscribe".equals(event)) {
            onSubscribed(root);
            return;
        }
        String channel = root.path("arg").path("channel").asText("");
        if ("orders".equals(channel) && root.path("data").isArray()) {
            AuthUserContext.runAs(owner, () -> root.path("data").forEach(orderEvents::apply));
        } else if (("positions".equals(channel) || "account".equals(channel))
                && root.path("data").isArray()) {
            accountOrPositionUpdate.run();
        }
    }

    synchronized void disconnected(String disconnectReason) {
        state = OkxPrivateWebSocketState.DEGRADED;
        reason = compact(disconnectReason);
        sender = null;
        subscribed.clear();
    }

    synchronized void markConnecting(boolean reconnecting) {
        state = reconnecting ? OkxPrivateWebSocketState.RECONNECTING : OkxPrivateWebSocketState.CONNECTING;
        reason = reconnecting ? "RECONNECT_PENDING" : "CONNECT_PENDING";
    }

    synchronized void fail(String failureReason) {
        state = OkxPrivateWebSocketState.FAILED;
        reason = compact(failureReason);
    }

    void ping() {
        Consumer<String> current = sender;
        if (current != null) {
            current.accept("ping");
        }
    }

    OkxPrivateWebSocketState state() {
        return state;
    }

    String reason() {
        return reason;
    }

    Instant lastMessageAt() {
        return lastMessageAt;
    }

    private void onLogin(JsonNode root) {
        if (!"0".equals(root.path("code").asText(""))) {
            state = OkxPrivateWebSocketState.FAILED;
            reason = "WS_LOGIN_FAILED:" + compact(root.path("code").asText("UNKNOWN"));
            return;
        }
        state = OkxPrivateWebSocketState.SUBSCRIBING;
        reason = "REQUIRED_SUBSCRIPTIONS_PENDING";
        ObjectNode request = objectMapper.createObjectNode();
        request.put("id", "privatecore");
        request.put("op", "subscribe");
        ArrayNode args = request.putArray("args");
        args.addObject().put("channel", "orders").put("instType", "SWAP");
        args.addObject().put("channel", "positions").put("instType", "SWAP");
        args.addObject().put("channel", "account");
        send(request);
    }

    private void onSubscribed(JsonNode root) {
        if (state != OkxPrivateWebSocketState.SUBSCRIBING) {
            return;
        }
        String channel = root.path("arg").path("channel").asText("");
        if (REQUIRED_CHANNELS.contains(channel)) {
            subscribed.add(channel);
        }
        if (!subscribed.containsAll(REQUIRED_CHANNELS)) {
            return;
        }
        try {
            if (reconciliation.getAsBoolean()) {
                state = OkxPrivateWebSocketState.READY;
                reason = "AUTHENTICATED_SUBSCRIBED_RECONCILED";
            } else {
                state = OkxPrivateWebSocketState.DEGRADED;
                reason = "REST_RECONCILIATION_FAILED";
            }
        } catch (RuntimeException ex) {
            state = OkxPrivateWebSocketState.DEGRADED;
            reason = "REST_RECONCILIATION_FAILED";
        }
    }

    private void send(JsonNode message) {
        try {
            sender.accept(objectMapper.writeValueAsString(message));
        } catch (Exception ex) {
            state = OkxPrivateWebSocketState.FAILED;
            reason = "WS_SEND_FAILED";
        }
    }

    private static String compact(String value) {
        if (value == null || value.isBlank()) {
            return "UNKNOWN";
        }
        String compact = value.replaceAll("\\s+", " ").trim();
        return compact.substring(0, Math.min(compact.length(), 120));
    }
}
