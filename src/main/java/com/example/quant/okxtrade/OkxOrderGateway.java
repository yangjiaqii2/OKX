package com.example.quant.okxtrade;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

public interface OkxOrderGateway {
    JsonNode placeOrder(Map<String, String> payload);
}
