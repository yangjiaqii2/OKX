package com.example.quant.okxtrade;

import com.example.quant.crypto.OkxRestClient;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class OkxRestOrderGateway implements OkxOrderGateway {
    private final OkxRestClient okxRestClient;

    public OkxRestOrderGateway(OkxRestClient okxRestClient) {
        this.okxRestClient = okxRestClient;
    }

    @Override
    public JsonNode placeOrder(Map<String, String> payload) {
        return okxRestClient.privatePost("/api/v5/trade/order", payload);
    }
}
