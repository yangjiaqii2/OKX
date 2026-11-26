package com.example.quant.crypto.provider.coingecko;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.mockito.Mockito.verify;

class CoinGeckoClientImplTest {

    @Test
    void proModeUsesProEndpointAndHeader() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("[{\"id\":\"bitcoin\",\"symbol\":\"btc\",\"market_cap\":100}]");
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);

        CoinGeckoClientImpl client = new CoinGeckoClientImpl(
                httpClient, new ObjectMapper(), "secret", "PRO");
        client.fetchBySymbols(List.of("BTC"));

        ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient, times(2)).send(request.capture(), any(HttpResponse.BodyHandler.class));
        assertThat(request.getValue().uri().toString())
                .startsWith("https://pro-api.coingecko.com/api/v3/coins/markets");
        assertThat(request.getValue().headers().firstValue("x-cg-pro-api-key"))
                .contains("secret");
        assertThat(request.getValue().headers().firstValue("x-cg-demo-api-key"))
                .isEmpty();
    }

    @Test
    void resolvesAmbiguityByVerifiedIdAndOmitsUnverifiedCollisions() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<String> catalog = mock(HttpResponse.class);
        HttpResponse<String> market = mock(HttpResponse.class);
        when(catalog.statusCode()).thenReturn(200);
        when(catalog.body()).thenReturn("""
                [{"id":"bitcoin","symbol":"btc"},{"id":"wrapped-btc","symbol":"btc"},
                 {"id":"alfa","symbol":"alfa"},{"id":"other-alfa","symbol":"alfa"}]
                """);
        when(market.statusCode()).thenReturn(200);
        when(market.body()).thenReturn("""
                [{"id":"bitcoin","symbol":"btc","market_cap":100},
                 {"id":"wrapped-btc","symbol":"btc","market_cap":10}]
                """);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(catalog, market, market);
        var client = new CoinGeckoClientImpl(httpClient, new ObjectMapper(), "secret", "DEMO");

        assertThat(client.fetchBySymbols(List.of("BTC", "ALFA")))
                .extracting(CoinGeckoMarketData::id).containsExactly("bitcoin");
        assertThat(client.fetchBySymbols(List.of("BTC"))).hasSize(1);
        var requests = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient, times(3)).send(requests.capture(), any(HttpResponse.BodyHandler.class));
        assertThat(requests.getAllValues()).extracting(request -> request.uri().getPath())
                .containsExactly("/api/v3/coins/list", "/api/v3/coins/markets", "/api/v3/coins/markets");
        assertThat(requests.getAllValues().get(1).uri().getQuery()).isEqualTo("vs_currency=usd&ids=bitcoin");
    }

    @Test
    void upstreamServerFailureDoesNotLookLikeAnEmptyCatalog() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(503);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        var client = new CoinGeckoClientImpl(httpClient, new ObjectMapper(), "secret", "DEMO");

        assertThatThrownBy(() -> client.fetchBySymbols(List.of("BTC")))
                .isInstanceOf(CoinGeckoRequestException.class).hasMessageContaining("503");
    }

    @Test
    void interruptedRequestIsTypedAndInterruptFlagIsPreserved() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new InterruptedException("cancelled"));
        CoinGeckoClientImpl client = new CoinGeckoClientImpl(
                httpClient, new ObjectMapper(), "secret", "DEMO");

        try {
            assertThatThrownBy(() -> client.fetchBySymbols(List.of("BTC")))
                    .isInstanceOf(CoinGeckoRequestException.class)
                    .extracting("kind")
                    .isEqualTo(CoinGeckoRequestException.Kind.INTERRUPTED);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void http429IsTypedAsUpstreamRateLimit() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(429);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);
        CoinGeckoClientImpl client = new CoinGeckoClientImpl(
                httpClient, new ObjectMapper(), "secret", "DEMO");

        assertThatThrownBy(() -> client.fetchBySymbols(List.of("BTC")))
                .isInstanceOf(CoinGeckoRequestException.class)
                .extracting("kind")
                .isEqualTo(CoinGeckoRequestException.Kind.RATE_LIMITED);
    }
}
