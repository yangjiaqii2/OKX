package com.example.quant.risk;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "daily_risk_entry_claim", uniqueConstraints =
        @UniqueConstraint(name = "uk_daily_risk_entry", columnNames = {"user_name", "trade_day", "entry_key"}))
public class DailyRiskEntryClaimEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "user_name", nullable = false, length = 128)
    private String userName;
    @Column(name = "trade_day", nullable = false)
    private LocalDate tradeDay;
    @Column(name = "entry_key", nullable = false, length = 128)
    private String entryKey;
    @Column(name = "claimed_at", nullable = false)
    private Instant claimedAt;
    @Column(name = "outcome_recorded", nullable = false)
    private boolean outcomeRecorded;
    @Column(name = "net_pnl", precision = 30, scale = 12)
    private BigDecimal netPnl;

    public String getUserName() { return userName; }
    public void setUserName(String value) { userName = value; }
    public LocalDate getTradeDay() { return tradeDay; }
    public void setTradeDay(LocalDate value) { tradeDay = value; }
    public String getEntryKey() { return entryKey; }
    public void setEntryKey(String value) { entryKey = value; }
    public void setClaimedAt(Instant value) { claimedAt = value; }
    public boolean isOutcomeRecorded() { return outcomeRecorded; }
    public void setOutcomeRecorded(boolean value) { outcomeRecorded = value; }
    public void setNetPnl(BigDecimal value) { netPnl = value; }
}
