package com.example.quant.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.quant.account.OkxAccountBindingService;
import com.example.quant.account.OkxCredentialCodec;
import com.example.quant.account.OkxCredentialStore;
import com.example.quant.account.StoredOkxCredential;
import com.example.quant.config.OkxProperties;
import com.example.quant.config.TradingProperties;
import com.example.quant.config.TradingMode;
import com.example.quant.okxtrade.OkxRestOrderGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class OkxRestClientTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "{\"code\":\"50004\",\"msg\":\"API endpoint request timeout\",\"data\":[]}",
            "{\"code\":\"1\",\"data\":[{\"sCode\":\"50004\",\"sMsg\":\"timeout\"}]}"})
    void timeoutResponseDoesNotMeanExchangeRejection(String response) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v5/public/time", exchange -> {
            byte[] body = "{\"code\":\"0\",\"data\":[{\"ts\":\"1700000000123\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body); exchange.close();
        });
        server.createContext("/timeout", exchange -> {
            byte[] body = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        try {
            assertThatThrownBy(() -> clientFor(server).privatePost("/timeout", Map.of("clOrdId", "timeout-test")))
                    .isNotInstanceOf(com.example.quant.okxtrade.OkxApiRejectedException.class)
                    .hasMessageContaining("50004");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void tradingWritesFailClosedWithoutRetrying429Or500() throws Exception {
        AtomicInteger rateLimitedCalls = new AtomicInteger();
        AtomicInteger serverErrorCalls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v5/public/time", exchange -> {
            byte[] body = "{\"code\":\"0\",\"data\":[{\"ts\":\"1700000000123\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/rate-limited", exchange -> {
            rateLimitedCalls.incrementAndGet();
            byte[] body = "{\"code\":\"50011\",\"msg\":\"Rate limit reached\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(429, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/server-error", exchange -> {
            serverErrorCalls.incrementAndGet();
            byte[] body = "temporary failure".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            OkxRestClient client = clientFor(server);

            assertThatThrownBy(() -> client.privatePost("/rate-limited", Map.of("clOrdId", "stable-1")))
                    .hasMessageContaining("OKX HTTP 429");
            assertThatThrownBy(() -> client.privatePost("/server-error", Map.of("clOrdId", "stable-2")))
                    .hasMessageContaining("OKX HTTP 500");

            assertThat(rateLimitedCalls).hasValue(1);
            assertThat(serverErrorCalls).hasValue(1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void malformedTradingResponseFailsClosed() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v5/public/time", exchange -> {
            byte[] body = "{\"code\":\"0\",\"data\":[{\"ts\":\"1700000000123\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/malformed", exchange -> {
            calls.incrementAndGet();
            byte[] body = "not-json".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            OkxRestClient client = clientFor(server);

            assertThatThrownBy(() -> client.privatePost("/malformed", Map.of("clOrdId", "stable-3")))
                    .hasMessageContaining("OKX request failed");
            assertThat(calls).hasValue(1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void connectionResetDuringTradingWriteFailsWithoutRetry() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v5/public/time", exchange -> {
            byte[] body = "{\"code\":\"0\",\"data\":[{\"ts\":\"1700000000123\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/reset", exchange -> {
            calls.incrementAndGet();
            exchange.close();
        });
        server.start();
        try {
            OkxRestClient client = clientFor(server);

            assertThatThrownBy(() -> client.privatePost("/reset", Map.of("clOrdId", "stable-4")))
                    .hasMessageContaining("OKX request failed");
            assertThat(calls).hasValue(1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void demoPrivateRequestsCarrySimulatedTradingHeaderWhileLiveRequestsDoNot() throws Exception {
        AtomicReference<String> demoHeader = new AtomicReference<>();
        AtomicReference<String> liveHeader = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v5/public/time", exchange -> {
            byte[] body = "{\"code\":\"0\",\"data\":[{\"ts\":\"1700000000123\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/demo", exchange -> {
            demoHeader.set(exchange.getRequestHeaders().getFirst("x-simulated-trading"));
            byte[] body = "{\"code\":\"0\",\"data\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/live", exchange -> {
            liveHeader.set(exchange.getRequestHeaders().getFirst("x-simulated-trading"));
            byte[] body = "{\"code\":\"0\",\"data\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            OkxProperties properties = new OkxProperties(
                    new OkxProperties.Api("http://127.0.0.1:" + server.getAddress().getPort(), "", "", ""),
                    new OkxProperties.Websocket("", "", 0, 0, 0));
            OkxRestClient demo = new OkxRestClient(properties, bindingService(), new OkxSigner(),
                    new ObjectMapper(), HttpClient.newHttpClient(), TradingMode.DEMO);
            OkxRestClient live = new OkxRestClient(properties, bindingService(), new OkxSigner(),
                    new ObjectMapper(), HttpClient.newHttpClient(), TradingMode.LIVE);

            demo.privateGet("/demo");
            live.privateGet("/live");

            assertThat(demoHeader.get()).isEqualTo("1");
            assertThat(liveHeader.get()).isNull();
        } finally {
            server.stop(0);
        }
    }
    @Test
    void privateRequestUsesOkxServerTimeForTimestampHeader() throws Exception {
        long okxServerMillis = 1_700_000_000_123L;
        AtomicReference<String> capturedTimestamp = new AtomicReference<>("");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v5/public/time", exchange -> {
            byte[] body = ("{\"code\":\"0\",\"data\":[{\"ts\":\"" + okxServerMillis + "\"}]}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/api/v5/account/balance", exchange -> {
            capturedTimestamp.set(exchange.getRequestHeaders().getFirst("OK-ACCESS-TIMESTAMP"));
            byte[] body = "{\"code\":\"0\",\"data\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            OkxRestClient client = new OkxRestClient(
                    new OkxProperties(
                            new OkxProperties.Api("http://127.0.0.1:" + server.getAddress().getPort(), "", "", ""),
                            new OkxProperties.Websocket("", "", 0, 0, 0)
                    ),
                    bindingService(),
                    new OkxSigner(),
                    new ObjectMapper(),
                    HttpClient.newHttpClient()
            );

            client.privateGet("/api/v5/account/balance");

            long signedMillis = Instant.parse(capturedTimestamp.get()).toEpochMilli();
            assertThat(Math.abs(signedMillis - okxServerMillis)).isLessThan(5_000L);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void failedOkxResponseIncludesPerOperationMessage() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v5/public/time", exchange -> {
            byte[] body = "{\"code\":\"0\",\"data\":[{\"ts\":\"1700000000123\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/api/v5/trade/order", exchange -> {
            byte[] body = """
                    {"code":"1","msg":"All operations failed","data":[{"sCode":"51008","sMsg":"Order failed. Insufficient balance."}]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            OkxRestClient client = new OkxRestClient(
                    new OkxProperties(
                            new OkxProperties.Api("http://127.0.0.1:" + server.getAddress().getPort(), "", "", ""),
                            new OkxProperties.Websocket("", "", 0, 0, 0)
                    ),
                    bindingService(),
                    new OkxSigner(),
                    new ObjectMapper(),
                    HttpClient.newHttpClient()
            );

            assertThatThrownBy(() -> client.privatePost("/api/v5/trade/order", Map.of("instId", "BTC-USDT-SWAP")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("All operations failed")
                    .hasMessageContaining("51008")
                    .hasMessageContaining("Insufficient balance");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void cancelAlgoGatewayPostsArrayPayloadRequiredByOkx() throws Exception {
        AtomicReference<String> capturedBody = new AtomicReference<>("");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v5/public/time", exchange -> {
            byte[] body = "{\"code\":\"0\",\"data\":[{\"ts\":\"1700000000123\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/api/v5/trade/cancel-algos", exchange -> {
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "{\"code\":\"0\",\"data\":[{\"algoClOrdId\":\"lc123SL\",\"sCode\":\"0\",\"sMsg\":\"\"}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            OkxRestClient client = new OkxRestClient(
                    new OkxProperties(
                            new OkxProperties.Api("http://127.0.0.1:" + server.getAddress().getPort(), "", "", ""),
                            new OkxProperties.Websocket("", "", 0, 0, 0)
                    ),
                    bindingService(),
                    new OkxSigner(),
                    new ObjectMapper(),
                    HttpClient.newHttpClient()
            );
            OkxRestOrderGateway gateway = new OkxRestOrderGateway(
                    client,
                    new TradingProperties("SEMI_AUTO", true, true, 120, false)
            );

            gateway.cancelAlgoOrder(Map.of("instId", "BTC-USDT-SWAP", "algoClOrdId", "lc123SL"));

            assertThat(capturedBody.get()).startsWith("[");
            assertThat(capturedBody.get()).contains("\"instId\":\"BTC-USDT-SWAP\"");
            assertThat(capturedBody.get()).contains("\"algoClOrdId\":\"lc123SL\"");
        } finally {
            server.stop(0);
        }
    }

    private static OkxAccountBindingService bindingService() {
        return new OkxAccountBindingService(
                new TradingProperties("SEMI_AUTO", true, false, 120, false),
                new FixedOkxCredentialStore(),
                new PlainOkxCredentialCodec()
        );
    }

    private static OkxRestClient clientFor(HttpServer server) {
        return new OkxRestClient(
                new OkxProperties(
                        new OkxProperties.Api("http://127.0.0.1:" + server.getAddress().getPort(), "", "", ""),
                        new OkxProperties.Websocket("", "", 0, 0, 0)
                ),
                bindingService(),
                new OkxSigner(),
                new ObjectMapper(),
                HttpClient.newHttpClient(),
                TradingMode.DEMO
        );
    }

    private static final class FixedOkxCredentialStore implements OkxCredentialStore {
        @Override
        public Optional<StoredOkxCredential> findActive(String username) {
            return Optional.of(new StoredOkxCredential("key", "secret", "passphrase", "key"));
        }

        @Override
        public void saveActive(String username, StoredOkxCredential credential) {
            // 测试只关心签名请求；生产存储会在这里持久化旧凭据格式迁移。
        }

        @Override
        public void deleteActive(String username) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class PlainOkxCredentialCodec implements OkxCredentialCodec {
        @Override
        public String encode(String value) {
            return value;
        }

        @Override
        public String decode(String value) {
            return value;
        }
    }
}
