package com.example.quant.agent.plan;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "trade_take_profit_plan")
public class TradeTakeProfitPlanEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trade_plan_id", nullable = false, length = 36)
    private String tradePlanId;

    @Column(name = "level_no", nullable = false)
    private int levelNo;

    @Column(name = "price", nullable = false, precision = 30, scale = 12)
    private BigDecimal price;

    @Column(name = "position_percent", nullable = false, precision = 8, scale = 4)
    private BigDecimal positionPercent;

    @Column(name = "condition_text", length = 500)
    private String conditionText;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public void setTradePlanId(String tradePlanId) {
        this.tradePlanId = tradePlanId;
    }

    public void setLevelNo(int levelNo) {
        this.levelNo = levelNo;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public void setPositionPercent(BigDecimal positionPercent) {
        this.positionPercent = positionPercent;
    }

    public void setConditionText(String conditionText) {
        this.conditionText = conditionText;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
