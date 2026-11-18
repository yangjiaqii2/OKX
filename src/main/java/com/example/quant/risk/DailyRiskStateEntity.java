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
@Table(name = "daily_risk_state", uniqueConstraints =
        @UniqueConstraint(name = "uk_daily_risk_owner_day", columnNames = {"user_name", "trade_day"}))
public class DailyRiskStateEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "user_name", nullable = false, length = 128)
    private String userName;
    @Column(name = "trade_day", nullable = false)
    private LocalDate tradeDay;
    @Column(name = "starting_equity", nullable = false, precision = 30, scale = 12)
    private BigDecimal startingEquity = BigDecimal.ZERO;
    @Column(name = "entry_count", nullable = false)
    private int entryCount;
    @Column(name = "consecutive_losses", nullable = false)
    private int consecutiveLosses;
    @Column(name = "last_loss_at")
    private Instant lastLossAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public String getUserName() { return userName; }
    public void setUserName(String value) { userName = value; }
    public LocalDate getTradeDay() { return tradeDay; }
    public void setTradeDay(LocalDate value) { tradeDay = value; }
    public BigDecimal getStartingEquity() { return startingEquity; }
    public void setStartingEquity(BigDecimal value) { startingEquity = value; }
    public int getEntryCount() { return entryCount; }
    public void setEntryCount(int value) { entryCount = value; }
    public int getConsecutiveLosses() { return consecutiveLosses; }
    public void setConsecutiveLosses(int value) { consecutiveLosses = value; }
    public Instant getLastLossAt() { return lastLossAt; }
    public void setLastLossAt(Instant value) { lastLossAt = value; }
    public void setUpdatedAt(Instant value) { updatedAt = value; }
}
