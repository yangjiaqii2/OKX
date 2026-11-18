package com.example.quant.system;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.quant.config.SystemFxRateProperties;
import java.io.StringReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

@Component
public class EcbFxRateClient {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient httpClient;
    private final URI endpoint;
    private final URI fallbackEndpoint;
    private final Duration requestTimeout;

    public EcbFxRateClient(SystemFxRateProperties properties) {
        Duration connectTimeout = Duration.ofSeconds(Math.max(1, properties.connectTimeoutSeconds()));
        this.httpClient = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
        this.endpoint = URI.create(properties.url());
        this.fallbackEndpoint = URI.create(properties.fallbackUrl());
        this.requestTimeout = Duration.ofSeconds(Math.max(1, properties.readTimeoutSeconds()));
    }

    public Quote fetch() {
        RuntimeException primaryFailure;
        try {
            return parse(get(endpoint, "application/xml,text/xml"), Instant.now());
        } catch (RuntimeException ex) {
            primaryFailure = ex;
        }
        try {
            return parseFrankfurter(get(fallbackEndpoint, "application/json"), Instant.now());
        } catch (RuntimeException ex) {
            ex.addSuppressed(primaryFailure);
            throw new IllegalStateException("FX_RATE_SOURCES_UNAVAILABLE", ex);
        }
    }

    private String get(URI uri, String accept) {
        try {
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(requestTimeout)
                    .header("Accept", accept).GET().build();
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw new IllegalStateException("FX_HTTP_" + response.statusCode());
            }
            return response.body();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("FX_REQUEST_INTERRUPTED", ex);
        } catch (Exception ex) {
            throw ex instanceof IllegalStateException state ? state
                    : new IllegalStateException("FX_REQUEST_FAILED", ex);
        }
    }

    static Quote parse(String xml, Instant fetchedAt) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            NodeList cubes = factory.newDocumentBuilder()
                    .parse(new InputSource(new StringReader(xml == null ? "" : xml)))
                    .getElementsByTagName("Cube");
            BigDecimal usd = null;
            BigDecimal cny = null;
            for (int i = 0; i < cubes.getLength(); i++) {
                Element cube = (Element) cubes.item(i);
                String currency = cube.getAttribute("currency");
                if ("USD".equals(currency)) {
                    usd = decimal(cube.getAttribute("rate"));
                } else if ("CNY".equals(currency)) {
                    cny = decimal(cube.getAttribute("rate"));
                }
            }
            if (usd == null || cny == null || usd.signum() <= 0 || cny.signum() <= 0) {
                throw new IllegalStateException("ECB_RATE_MISSING");
            }
            return new Quote(cny.divide(usd, 8, RoundingMode.HALF_UP), fetchedAt, "ECB");
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("ECB_XML_INVALID", ex);
        }
    }

    static Quote parseFrankfurter(String json, Instant fetchedAt) {
        try {
            JsonNode rate = JSON.readTree(json == null ? "" : json).path("rates").path("CNY");
            if (!rate.isNumber() || rate.decimalValue().signum() <= 0) {
                throw new IllegalStateException("FRANKFURTER_RATE_MISSING");
            }
            return new Quote(rate.decimalValue(), fetchedAt, "FRANKFURTER_ECB");
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("FRANKFURTER_JSON_INVALID", ex);
        }
    }

    private static BigDecimal decimal(String value) {
        try {
            return value == null || value.isBlank() ? null : new BigDecimal(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    public record Quote(BigDecimal rate, Instant fetchedAt, String source) {
        public Quote(BigDecimal rate, Instant fetchedAt) {
            this(rate, fetchedAt, "ECB");
        }
    }
}
