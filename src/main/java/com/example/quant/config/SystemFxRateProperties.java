package com.example.quant.config;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "quant.system.fx-rate")
public class SystemFxRateProperties {
    private String base = "USD";
    private String quote = "CNY";
    private BigDecimal rate = new BigDecimal("7.2");
    private String source = "CONFIGURED";

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
}
