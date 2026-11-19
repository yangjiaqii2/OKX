package com.example.quant.okxtrade;

import com.example.quant.order.PendingOrder;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

public final class EntryOrderReconciliation {
    private EntryOrderReconciliation() {}

    /** A cancel acknowledgement alone cannot establish the final filled quantity. */
    public static boolean ensureTerminal(OkxOrderGateway gateway, PendingOrder order) {
        // externalOrderId is reused for the close ACK after markCloseSubmitted.
        String id = order.clientOrderId();
        if (id == null || id.isBlank()) return false;
        Map<String, String> key = Map.of("instId", order.instId(), "clOrdId", id);
        String state = state(gateway.queryOrder(key));
        if (terminal(state)) return true;
        if (!"live".equals(state) && !"partially_filled".equals(state)) return false;
        OkxCancelAcknowledgement.requireSuccess(gateway.cancelOrder(key));
        return terminal(state(gateway.queryOrder(key)));
    }

    private static String state(JsonNode response) {
        return response == null ? "" : response.path("data").path(0).path("state").asText("");
    }

    private static boolean terminal(String state) {
        return "filled".equals(state) || "canceled".equals(state);
    }
}
