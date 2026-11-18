package com.example.quant.agent.evaluation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "counterfactual_exit")
public class CounterfactualExitEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name="user_name",nullable=false,length=128) private String userName;
    @Column(name="pending_order_id",nullable=false,length=36) private String pendingOrderId;
    @Column(name="trade_plan_id",length=36) private String tradePlanId;
    @Column(name="policy",nullable=false,length=24) private String policy;
    @Column(name="inst_id",nullable=false,length=64) private String instId;
    @Column(name="direction",nullable=false,length=8) private String direction;
    @Column(name="entry_time",nullable=false) private Instant entryTime;
    @Column(name="entry_price",nullable=false,precision=30,scale=12) private BigDecimal entryPrice;
    @Column(name="quantity",nullable=false,precision=30,scale=12) private BigDecimal quantity;
    @Column(name="risk_distance",nullable=false,precision=30,scale=12) private BigDecimal riskDistance;
    @Column(name="exit_time") private Instant exitTime;
    @Column(name="exit_price",precision=30,scale=12) private BigDecimal exitPrice;
    @Column(name="gross_pnl",precision=30,scale=12) private BigDecimal grossPnl;
    @Column(name="fee",precision=30,scale=12) private BigDecimal fee;
    @Column(name="net_pnl",precision=30,scale=12) private BigDecimal netPnl;
    @Column(name="pnl_r",precision=18,scale=8) private BigDecimal pnlR;
    @Column(name="hold_minutes") private Long holdMinutes;
    @Column(name="status",nullable=false,length=16) private String status;
    @Column(name="signal_venue",nullable=false,length=32) private String signalVenue;
    @Column(name="execution_venue",nullable=false,length=32) private String executionVenue;
    @Column(name="data_quality",nullable=false,length=64) private String dataQuality;
    @Column(name="updated_at",nullable=false) private Instant updatedAt;

    public String getPolicy(){return policy;} public String getStatus(){return status;}
    public BigDecimal getGrossPnl(){return grossPnl;} public BigDecimal getNetPnl(){return netPnl;}
    public BigDecimal getFee(){return fee;}
    public BigDecimal getPnlR(){return pnlR;} public Long getHoldMinutes(){return holdMinutes;}
    public String getDataQuality(){return dataQuality;} public String getPendingOrderId(){return pendingOrderId;}
    public void setUserName(String v){userName=v;} public void setPendingOrderId(String v){pendingOrderId=v;}
    public void setTradePlanId(String v){tradePlanId=v;} public void setPolicy(String v){policy=v;}
    public void setInstId(String v){instId=v;} public void setDirection(String v){direction=v;}
    public void setEntryTime(Instant v){entryTime=v;} public void setEntryPrice(BigDecimal v){entryPrice=v;}
    public void setQuantity(BigDecimal v){quantity=v;} public void setRiskDistance(BigDecimal v){riskDistance=v;}
    public void setExitTime(Instant v){exitTime=v;} public void setExitPrice(BigDecimal v){exitPrice=v;}
    public void setGrossPnl(BigDecimal v){grossPnl=v;} public void setFee(BigDecimal v){fee=v;}
    public void setNetPnl(BigDecimal v){netPnl=v;} public void setPnlR(BigDecimal v){pnlR=v;}
    public void setHoldMinutes(Long v){holdMinutes=v;} public void setStatus(String v){status=v;}
    public void setSignalVenue(String v){signalVenue=v;} public void setExecutionVenue(String v){executionVenue=v;}
    public void setDataQuality(String v){dataQuality=v;} public void setUpdatedAt(Instant v){updatedAt=v;}
}
