package com.example.quant.okxtrade;

import com.fasterxml.jackson.databind.JsonNode;

/** A successful HTTP request is not proof that every order was cancelled. */
public final class OkxCancelAcknowledgement {
    private OkxCancelAcknowledgement() {}

    public static void requireSuccess(JsonNode response) {
        JsonNode data = response == null ? null : response.path("data");
        if (response == null || !"0".equals(response.path("code").asText(""))
                || data == null || !data.isArray() || data.isEmpty()) {
            throw new IllegalStateException("OKX_CANCEL_UNCONFIRMED: missing successful response");
        }
        for (JsonNode item : data) {
            if (!"0".equals(item.path("sCode").asText(""))) {
                throw new IllegalStateException("OKX_CANCEL_UNCONFIRMED: sCode="
                        + item.path("sCode").asText("") + ", sMsg=" + item.path("sMsg").asText(""));
            }
        }
    }
}
