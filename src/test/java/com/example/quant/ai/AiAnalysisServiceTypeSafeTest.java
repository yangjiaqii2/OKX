package com.example.quant.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.quant.config.AiProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AiAnalysisServiceTypeSafeTest {
    @Test
    void sendsTypedQuestionsToTypeSafeAndReturnsStructuredAnswers() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/systemone", AiAnalysisServiceTypeSafeTest::respondOk);
        server.start();
        try {
            AiProperties properties = new AiProperties(
                    true, "TYPESAFE", null, null, null, 5, 0,
                    "jev-test-key", "http://127.0.0.1:" + server.getAddress().getPort(), "jev-latest");
            AiAnalysisService service = new AiAnalysisService(properties, new ObjectMapper());
            Map<String, Object> questions = new LinkedHashMap<>();
            questions.put("decision", Map.of(
                    "type", "choice",
                    "instructions", "Should this candidate be traded?",
                    "criteria", Map.of("TRADE", "Trade", "WAIT", "Wait")));
            questions.put("quality", Map.of(
                    "type", "score",
                    "instructions", "How strong is this setup?",
                    "criteria", java.util.List.of("Weak", "Strong")));
            questions.put("risk_ok", Map.of(
                    "type", "noul",
                    "instructions", "Is this setup safe to trade?"));

            JsonNode result = service.completeTyped(Map.of("candidate", "BTC-USDT-SWAP"), questions);

            assertThat(result.path("answers").path("decision").path("choice").asText()).isEqualTo("TRADE");
            assertThat(result.path("answers").path("quality").path("score").asDouble()).isEqualTo(1.0);
            assertThat(result.path("answers").path("risk_ok").path("noul").asDouble()).isEqualTo(0.9);
        } finally {
            server.stop(0);
        }
    }

    private static void respondOk(HttpExchange exchange) throws IOException {
        byte[] body = ("{\"model\":\"jev-1.13.0\",\"answers\":{" +
                "\"decision\":{\"type\":\"choice\",\"choice\":\"TRADE\",\"confidence\":0.92}," +
                "\"quality\":{\"type\":\"score\",\"score\":1.0,\"confidence\":0.8}," +
                "\"risk_ok\":{\"type\":\"noul\",\"noul\":0.9}}}")
                .getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, body.length);
        try (var output = exchange.getResponseBody()) {
            output.write(body);
        }
    }
}
