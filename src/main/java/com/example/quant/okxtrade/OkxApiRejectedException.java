package com.example.quant.okxtrade;

import com.fasterxml.jackson.databind.JsonNode;

/** An explicit OKX response rejected the operation, rather than a transport failure. */
public class OkxApiRejectedException extends IllegalStateException {
    public OkxApiRejectedException(String message) {
        super(message);
    }

    public static IllegalStateException fromResponse(String code, String message) {
        if ("51016".equals(code)) {
            return new DuplicateClientOrderIdException(message);
        }
        // OKX explicitly states that 50004 does not establish success or failure.
        // Service/system failures also remain uncertain until read back.
        if (java.util.Set.of("50001", "50004", "50013", "50026").contains(code)) {
            return new IllegalStateException("OKX_RESPONSE_OUTCOME_UNKNOWN: code=" + code + "; " + message);
        }
        return new OkxApiRejectedException(message);
    }

    public static IllegalStateException fromResponse(JsonNode response, String message) {
        String code = response.path("code").asText("");
        if ("51016".equals(code)) {
            return fromResponse(code, message);
        }
        JsonNode data = response.path("data");
        if (data.isArray()) {
            for (JsonNode item : data) {
                IllegalStateException failure = fromResponse(item.path("sCode").asText(""), message);
                if (!(failure instanceof OkxApiRejectedException)) return failure;
            }
        }
        if ("1".equals(code) || "2".equals(code)) {
            // An aggregate error alone does not prove every requested item failed.
            if (!data.isArray() || data.isEmpty()) return new IllegalStateException(message);
            for (JsonNode item : data) {
                String itemCode = item.path("sCode").asText("");
                if (itemCode.isBlank() || "0".equals(itemCode)) return new IllegalStateException(message);
            }
        }
        return fromResponse(code, message);
    }

    public static final class DuplicateClientOrderIdException extends IllegalStateException {
        private DuplicateClientOrderIdException(String message) {
            super(message);
        }
    }
}
