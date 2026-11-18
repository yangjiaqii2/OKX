package com.example.quant.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "exchange_position_state")
public class ExchangePositionEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "user_name", nullable = false, length = 128)
    private String userName;
    @Column(name = "inst_id", nullable = false, length = 64)
    private String instId;
    @Column(name = "pos_side", nullable = false, length = 16)
    private String posSide;
    @Column(name = "size_value", precision = 30, scale = 12)
    private BigDecimal size;
    @Column(name = "avg_px", precision = 30, scale = 12)
    private BigDecimal averagePrice;
    @Column(name = "unrealized_pnl", precision = 30, scale = 12)
    private BigDecimal unrealizedPnl;
    @Column(name = "ownership", nullable = false, length = 16)
    private String ownership;
    @Column(name = "reason", nullable = false, length = 128)
    private String reason;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public String getUserName() { return userName; }
    public String getInstId() { return instId; }
    public String getPosSide() { return posSide; }
    public BigDecimal getSize() { return size; }
    public String getOwnership() { return ownership; }
    public String getReason() { return reason; }
    public void setUserName(String value) { userName = value; }
    public void setInstId(String value) { instId = value; }
    public void setPosSide(String value) { posSide = value; }
    public void setSize(BigDecimal value) { size = value; }
    public void setAveragePrice(BigDecimal value) { averagePrice = value; }
    public void setUnrealizedPnl(BigDecimal value) { unrealizedPnl = value; }
    public void setOwnership(String value) { ownership = value; }
    public void setReason(String value) { reason = value; }
    public void setUpdatedAt(Instant value) { updatedAt = value; }
}
