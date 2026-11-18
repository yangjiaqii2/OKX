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
@Table(name = "account_bill")
public class AccountBillEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "user_name", nullable = false, length = 128)
    private String userName;
    @Column(name = "bill_id", nullable = false, length = 128)
    private String billId;
    @Column(name = "bill_type", nullable = false, length = 16)
    private String type;
    @Column(name = "bill_sub_type", nullable = false, length = 16)
    private String subType;
    @Column(name = "category", nullable = false, length = 32)
    private String category;
    @Column(name = "currency", length = 32)
    private String currency;
    @Column(name = "inst_id", length = 64)
    private String instId;
    @Column(name = "ord_id", length = 128)
    private String ordId;
    @Column(name = "cl_ord_id", length = 64)
    private String clOrdId;
    @Column(name = "trade_id", length = 128)
    private String tradeId;
    @Column(name = "pnl", precision = 30, scale = 12)
    private BigDecimal pnl;
    @Column(name = "fee", precision = 30, scale = 12)
    private BigDecimal fee;
    @Column(name = "balance_change", precision = 30, scale = 12)
    private BigDecimal balanceChange;
    @Column(name = "bill_time", nullable = false)
    private Instant billTime;

    public String getBillId() { return billId; }
    public String getCategory() { return category; }
    public String getInstId() { return instId; }
    public String getOrdId() { return ordId; }
    public BigDecimal getPnl() { return pnl; }
    public BigDecimal getFee() { return fee; }
    public Instant getBillTime() { return billTime; }
    public void setUserName(String value) { userName = value; }
    public void setBillId(String value) { billId = value; }
    public void setType(String value) { type = value; }
    public void setSubType(String value) { subType = value; }
    public void setCategory(String value) { category = value; }
    public void setCurrency(String value) { currency = value; }
    public void setInstId(String value) { instId = value; }
    public void setOrdId(String value) { ordId = value; }
    public void setClOrdId(String value) { clOrdId = value; }
    public void setTradeId(String value) { tradeId = value; }
    public void setPnl(BigDecimal value) { pnl = value; }
    public void setFee(BigDecimal value) { fee = value; }
    public void setBalanceChange(BigDecimal value) { balanceChange = value; }
    public void setBillTime(Instant value) { billTime = value; }
}
