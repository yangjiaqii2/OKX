package com.example.quant.config;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "quant.system.fx-rate")
public class SystemFxRateProperties {
    private String base = "USD";
    private String quote = "CNY";
    private BigDecimal rate = new BigDecimal("7.0");
    private String source = "FALLBACK";
    private boolean enabled = true;
    private String url = "https://www.ecb.europa.eu/stats/eurofxref/eurofxref-daily.xml";
    private String fallbackUrl = "https://api.frankfurter.app/latest?from=USD&to=CNY";
    private int connectTimeoutSeconds = 3;
    private int readTimeoutSeconds = 5;

    public String base() {
        return base;
    }

    public void setBase(String base) {
        this.base = base;
    }

    public String quote() {
        return quote;
    }

    public void setQuote(String quote) {
        this.quote = quote;
    }

    public BigDecimal rate() {
        return rate;
    }

    public void setRate(BigDecimal rate) {
        this.rate = rate;
    }

    public String source() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public boolean enabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String url() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String fallbackUrl() { return fallbackUrl; }
    public void setFallbackUrl(String fallbackUrl) { this.fallbackUrl = fallbackUrl; }
    public int connectTimeoutSeconds() { return connectTimeoutSeconds; }
    public void setConnectTimeoutSeconds(int value) { connectTimeoutSeconds = value; }
    public int readTimeoutSeconds() { return readTimeoutSeconds; }
    public void setReadTimeoutSeconds(int value) { readTimeoutSeconds = value; }
}
