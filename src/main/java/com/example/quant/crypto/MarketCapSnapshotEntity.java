package com.example.quant.crypto;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/** Last successfully observed external market-cap snapshot for quota fallback. */
@Entity
@Table(name = "market_cap_snapshot")
public class MarketCapSnapshotEntity {
    @Id
    @Column(name = "base_symbol", nullable = false, length = 64)
    private String baseSymbol;

    @Column(name = "market_cap_usd", precision = 38, scale = 8)
    private BigDecimal marketCapUsd;

    @Column(name = "fdv_usd", precision = 38, scale = 8)
    private BigDecimal fdvUsd;

    @Column(name = "circulating_supply", precision = 38, scale = 8)
    private BigDecimal circulatingSupply;

    @Column(name = "max_supply", precision = 38, scale = 8)
    private BigDecimal maxSupply;

    @Column(name = "total_supply", precision = 38, scale = 8)
    private BigDecimal totalSupply;

    @Column(name = "snapshot_at", nullable = false)
    private Instant snapshotAt;

    @Column(name = "source", nullable = false, length = 32)
    private String source;

    public String getBaseSymbol() {
        return baseSymbol;
    }

    public void setBaseSymbol(String baseSymbol) {
        this.baseSymbol = baseSymbol;
    }

    public BigDecimal getMarketCapUsd() {
        return marketCapUsd;
    }

    public void setMarketCapUsd(BigDecimal marketCapUsd) {
        this.marketCapUsd = marketCapUsd;
    }

    public BigDecimal getFdvUsd() {
        return fdvUsd;
    }

    public void setFdvUsd(BigDecimal fdvUsd) {
        this.fdvUsd = fdvUsd;
    }

    public BigDecimal getCirculatingSupply() {
        return circulatingSupply;
    }

    public void setCirculatingSupply(BigDecimal circulatingSupply) {
        this.circulatingSupply = circulatingSupply;
    }

    public BigDecimal getMaxSupply() {
        return maxSupply;
    }

    public void setMaxSupply(BigDecimal maxSupply) {
        this.maxSupply = maxSupply;
    }

    public BigDecimal getTotalSupply() {
        return totalSupply;
    }

    public void setTotalSupply(BigDecimal totalSupply) {
        this.totalSupply = totalSupply;
    }

    public Instant getSnapshotAt() {
        return snapshotAt;
    }

    public void setSnapshotAt(Instant snapshotAt) {
        this.snapshotAt = snapshotAt;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }
}
