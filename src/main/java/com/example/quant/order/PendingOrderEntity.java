package com.example.quant.order;

import com.example.quant.market.MarketType;
import com.example.quant.tradeplan.TradePlanType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "pending_order_state")
public class PendingOrderEntity {
    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "market_type", nullable = false, length = 32)
    private String marketType;

    @Column(name = "inst_id", nullable = false, length = 64)
    private String instId;

    @Column(name = "action", nullable = false, length = 32)
    private String action;

    @Column(name = "side", length = 16)
    private String side;

    @Column(name = "pos_side", length = 16)
    private String posSide;

    @Column(name = "order_type", length = 32)
    private String orderType;

    @Column(name = "price", precision = 30, scale = 12)
    private BigDecimal price;

    @Column(name = "size_value", precision = 30, scale = 12)
    private BigDecimal size;

    @Column(name = "leverage")
    private int leverage;

    @Column(name = "td_mode", length = 32)
    private String tdMode;

    @Column(name = "stop_loss_price", precision = 30, scale = 12)
    private BigDecimal stopLossPrice;

    @Column(name = "take_profit_price", precision = 30, scale = 12)
    private BigDecimal takeProfitPrice;

    @Column(name = "max_loss_amount", precision = 30, scale = 12)
    private BigDecimal maxLossAmount;

    @Column(name = "margin_amount", precision = 30, scale = 12)
    private BigDecimal marginAmount;

    @Column(name = "budget_reservation_id", length = 36)
    private String budgetReservationId;

    @Column(name = "budget_allocation_json", columnDefinition = "TEXT")
    private String budgetAllocationJson;

    @Column(name = "client_order_id", length = 64)
    private String clientOrderId;

    @Column(name = "risk_reward_ratio", precision = 18, scale = 8)
    private BigDecimal riskRewardRatio;

    @Column(name = "signal_score")
    private int signalScore;

    @Column(name = "funding_rate", precision = 18, scale = 8)
    private BigDecimal fundingRate;

    @Column(name = "volatility", precision = 18, scale = 8)
    private BigDecimal volatility;

    @Column(name = "volume_24h", precision = 30, scale = 12)
    private BigDecimal volume24h;

    @Column(name = "trade_plan_id", length = 36)
    private String tradePlanId;

    @Column(name = "confirm_token", length = 128)
    private String confirmToken;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "user_confirmed")
    private boolean userConfirmed;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "executed_at")
    private Instant executedAt;

    @Column(name = "external_order_id", length = 128)
    private String externalOrderId;

    @Column(name = "reject_reason", columnDefinition = "TEXT")
    private String rejectReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expire_at", nullable = false)
    private Instant expireAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static PendingOrderEntity from(PendingOrder order) {
        PendingOrderEntity entity = new PendingOrderEntity();
        entity.id = order.id().toString();
        entity.marketType = order.marketType().name();
        entity.instId = order.instId();
        entity.action = order.action().name();
        entity.side = order.side();
        entity.posSide = order.posSide();
        entity.orderType = order.orderType();
        entity.price = order.price();
        entity.size = order.size();
        entity.leverage = order.leverage();
        entity.tdMode = order.tdMode();
        entity.stopLossPrice = order.stopLossPrice();
        entity.takeProfitPrice = order.takeProfitPrice();
        entity.maxLossAmount = order.maxLossAmount();
        entity.marginAmount = order.marginAmount();
        entity.budgetReservationId = order.budgetReservationId() == null ? null : order.budgetReservationId().toString();
        entity.budgetAllocationJson = order.budgetAllocationJson();
        entity.clientOrderId = order.clientOrderId();
        entity.riskRewardRatio = order.riskRewardRatio();
        entity.signalScore = order.signalScore();
        entity.fundingRate = order.fundingRate();
        entity.volatility = order.volatility();
        entity.volume24h = order.volume24h();
        entity.tradePlanId = order.tradePlanId() == null ? null : order.tradePlanId().toString();
        entity.confirmToken = order.confirmToken();
        entity.status = order.status().name();
        entity.userConfirmed = order.userConfirmed();
        entity.confirmedAt = order.confirmedAt();
        entity.submittedAt = order.submittedAt();
        entity.executedAt = order.executedAt();
        entity.externalOrderId = order.externalOrderId();
        entity.rejectReason = order.rejectReason();
        entity.createdAt = order.createdAt();
        entity.expireAt = order.expireAt();
        entity.updatedAt = Instant.now();
        return entity;
    }

    public PendingOrder toDomain() {
        PendingOrder order = new PendingOrder(
                UUID.fromString(id),
                MarketType.valueOf(marketType),
                instId,
                TradePlanType.valueOf(action),
                side,
                posSide,
                orderType,
                price,
                size,
                leverage,
                tdMode,
                stopLossPrice,
                takeProfitPrice,
                maxLossAmount,
                riskRewardRatio,
                signalScore,
                fundingRate,
                volatility,
                volume24h,
                uuid(tradePlanId),
                confirmToken,
                createdAt,
                expireAt
        );
        order.restoreState(
                size,
                maxLossAmount,
                marginAmount,
                uuid(budgetReservationId),
                budgetAllocationJson,
                clientOrderId,
                OrderStatus.valueOf(status),
                userConfirmed,
                confirmedAt,
                submittedAt,
                executedAt,
                externalOrderId,
                rejectReason
        );
        return order;
    }

    private static UUID uuid(String value) {
        return value == null || value.isBlank() ? null : UUID.fromString(value);
    }

    public String getId() {
        return id;
    }

    public String getBudgetReservationId() {
        return budgetReservationId;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public void setSubmittedAt(Instant submittedAt) {
        this.submittedAt = submittedAt;
    }

    public void setExternalOrderId(String externalOrderId) {
        this.externalOrderId = externalOrderId;
    }
}
