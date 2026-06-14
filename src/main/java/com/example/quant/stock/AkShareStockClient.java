package com.example.quant.stock;

import com.example.quant.config.AkShareProperties;
import com.example.quant.config.StockProperties;
import com.example.quant.stock.dto.StockCandidate;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class AkShareStockClient {
    private final AkShareProperties akShareProperties;
    private final StockProperties stockProperties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public AkShareStockClient(AkShareProperties akShareProperties, StockProperties stockProperties,
                              ObjectMapper objectMapper) {
        this.akShareProperties = akShareProperties;
        this.stockProperties = stockProperties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newHttpClient();
    }

    public List<StockCandidate> candidates() {
        String url = akShareProperties.baseUrl()
                + "/api/stock/candidates"
                + "?min_turnover=" + stockProperties.minTurnoverAmount()
                + "&min_change=" + stockProperties.minChangePercent()
                + "&max_change=" + stockProperties.maxChangePercent()
                + "&min_volume_ratio=" + stockProperties.volumeSpikeRatio()
                + "&limit=50";
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET().build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("AkShare HTTP " + response.statusCode());
            }
            return objectMapper.readValue(response.body(), new TypeReference<>() {
            });
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("AkShare request interrupted", ex);
        } catch (Exception ex) {
            throw new IllegalStateException("AkShare request failed. Start akshare-service first.", ex);
        }
    }
}
