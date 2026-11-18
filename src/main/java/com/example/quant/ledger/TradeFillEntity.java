package com.example.quant.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "trade_fill")
public class TradeFillEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "user_name", nullable = false, length = 128)
    private String userName;
    @Column(name = "trade_plan_id", length = 36)
    private String tradePlanId;
    @Column(name = "pending_order_id", length = 36)
    private String pendingOrderId;
    @Column(name = "inst_id", nullable = false, length = 64)
    private String instId;
    @Column(name = "ord_id", nullable = false, length = 128)
    private String ordId;
    @Column(name = "cl_ord_id", length = 64)
    private String clOrdId;
    @Column(name = "trade_id", nullable = false, length = 128)
    private String tradeId;
    @Column(name = "side", length = 16)
    private String side;
    @Column(name = "pos_side", length = 16)
    private String posSide;
    @Column(name = "fill_px", precision = 30, scale = 12)
    private BigDecimal fillPrice;
    @Column(name = "fill_sz", precision = 30, scale = 12)
    private BigDecimal fillSize;
    @Column(name = "fee", precision = 30, scale = 12)
    private BigDecimal fee;
    @Column(name = "fee_ccy", length = 32)
    private String feeCurrency;
    @Column(name = "fill_pnl", precision = 30, scale = 12)
    private BigDecimal fillPnl;
    @Column(name = "fill_time", nullable = false)
    private Instant fillTime;

    public String getTradeId() { return tradeId; }
    public String getUserName() { return userName; }
    public String getTradePlanId() { return tradePlanId; }
    public String getPendingOrderId() { return pendingOrderId; }
    public String getInstId() { return instId; }
    public String getOrdId() { return ordId; }
    public String getClOrdId() { return clOrdId; }
    public String getSide() { return side; }
    public String getPosSide() { return posSide; }
    public BigDecimal getFillPrice() { return fillPrice; }
    public BigDecimal getFillSize() { return fillSize; }
    public BigDecimal getFee() { return fee; }
    public String getFeeCurrency() { return feeCurrency; }
    public BigDecimal getFillPnl() { return fillPnl; }
    public Instant getFillTime() { return fillTime; }
    public void setUserName(String value) { userName = value; }
    public void setTradePlanId(String value) { tradePlanId = value; }
    public void setPendingOrderId(String value) { pendingOrderId = value; }
    public void setInstId(String value) { instId = value; }
    public void setOrdId(String value) { ordId = value; }
    public void setClOrdId(String value) { clOrdId = value; }
    public void setTradeId(String value) { tradeId = value; }
    public void setSide(String value) { side = value; }
    public void setPosSide(String value) { posSide = value; }
    public void setFillPrice(BigDecimal value) { fillPrice = value; }
    public void setFillSize(BigDecimal value) { fillSize = value; }
    public void setFee(BigDecimal value) { fee = value; }
    public void setFeeCurrency(String value) { feeCurrency = value; }
    public void setFillPnl(BigDecimal value) { fillPnl = value; }
    public void setFillTime(Instant value) { fillTime = value; }
}
