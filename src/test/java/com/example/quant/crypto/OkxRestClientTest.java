package com.example.quant.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.quant.account.OkxAccountBindingService;
import com.example.quant.account.OkxCredentialCodec;
import com.example.quant.account.OkxCredentialStore;
import com.example.quant.account.StoredOkxCredential;
import com.example.quant.config.OkxProperties;
import com.example.quant.config.TradingProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class OkxRestClientTest {
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

    private static OkxAccountBindingService bindingService() {
        return new OkxAccountBindingService(
                new TradingProperties("SEMI_AUTO", true, false, 120, false),
                new FixedOkxCredentialStore(),
                new PlainOkxCredentialCodec()
        );
    }

    private static final class FixedOkxCredentialStore implements OkxCredentialStore {
        @Override
        public Optional<StoredOkxCredential> findActive() {
            return Optional.of(new StoredOkxCredential("key", "secret", "passphrase", "key"));
        }

        @Override
        public void saveActive(StoredOkxCredential credential) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void deleteActive() {
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
