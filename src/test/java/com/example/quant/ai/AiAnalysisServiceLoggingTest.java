package com.example.quant.ai;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.quant.config.AiProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class AiAnalysisServiceLoggingTest {
    @Test
    void logsAiRequestStartAndCompletionWithoutSecrets() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", AiAnalysisServiceLoggingTest::respondOk);
        server.start();

        Logger logger = (Logger) LoggerFactory.getLogger(AiAnalysisService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            AiProperties properties = new AiProperties(
                    true, "test", "test-model", "secret-key",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", 5, 0,
                    null, null, null);
            AiAnalysisService service = new AiAnalysisService(properties, new ObjectMapper());

            assertThat(service.completeJson("system", "user").path("ok").asBoolean()).isTrue();

            List<String> messages = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
            assertThat(messages).anyMatch(message -> message.contains("AI request started"));
            assertThat(messages).anyMatch(message -> message.contains("AI request completed"));
            assertThat(messages).noneMatch(message -> message.contains("secret-key"));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
            server.stop(0);
        }
    }

    private static void respondOk(HttpExchange exchange) throws IOException {
        byte[] body = "{\"choices\":[{\"message\":{\"content\":\"{\\\"ok\\\":true}\"}}]}"
                .getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, body.length);
        try (var output = exchange.getResponseBody()) {
            output.write(body);
        }
    }
}
