package com.example.quant.order;

import com.example.quant.market.MarketType;
import com.example.quant.tradeplan.TradePlanType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

public class PendingOrder {
    private final UUID id;
    private final MarketType marketType;
    private final String instId;
    private final TradePlanType action;
    private final String side;
    private final String posSide;
    private final String orderType;
    private final BigDecimal price;
    private BigDecimal size;
    private int leverage;
    private final String tdMode;
    private final BigDecimal stopLossPrice;
    private final BigDecimal takeProfitPrice;
    private BigDecimal maxLossAmount;
    private BigDecimal marginAmount;
    private UUID budgetReservationId;
    private String budgetAllocationJson;
    private String clientOrderId;
    private final BigDecimal riskRewardRatio;
    private final int signalScore;
    private final BigDecimal fundingRate;
    private final BigDecimal volatility;
    private final BigDecimal volume24h;
    private final UUID tradePlanId;
    private final String confirmToken;
    private OrderStatus status;
    private boolean userConfirmed;
    private Instant confirmedAt;
    private Instant submittedAt;
    private Instant executedAt;
    private String externalOrderId;
    private String rejectReason;
    private final Instant createdAt;
    private final Instant expireAt;

    public PendingOrder(UUID id, MarketType marketType, String instId, TradePlanType action, String side, String posSide,
                        String orderType, BigDecimal price, BigDecimal size, int leverage, String tdMode,
                        BigDecimal stopLossPrice, BigDecimal takeProfitPrice, BigDecimal maxLossAmount,
                        BigDecimal riskRewardRatio, int signalScore, BigDecimal fundingRate, BigDecimal volatility,
                        BigDecimal volume24h, UUID tradePlanId, String confirmToken, Instant createdAt, Instant expireAt) {
        this.id = id;
        this.marketType = marketType;
        this.instId = instId;
        this.action = action;
        this.side = side;
        this.posSide = posSide;
        this.orderType = orderType;
        this.price = price;
        this.size = size;
        this.leverage = leverage;
        this.tdMode = tdMode;
        this.stopLossPrice = stopLossPrice;
        this.takeProfitPrice = takeProfitPrice;
        this.maxLossAmount = maxLossAmount;
        this.riskRewardRatio = riskRewardRatio;
        this.signalScore = signalScore;
        this.fundingRate = fundingRate;
        this.volatility = volatility;
        this.volume24h = volume24h;
        this.tradePlanId = tradePlanId;
        this.confirmToken = confirmToken;
        this.createdAt = createdAt;
        this.expireAt = expireAt;
        this.status = OrderStatus.PENDING_CONFIRM;
    }

    public UUID id() { return id; }
    public MarketType marketType() { return marketType; }
    public String instId() { return instId; }
    public TradePlanType action() { return action; }
    public String side() { return side; }
    public String posSide() { return posSide; }
    public String orderType() { return orderType; }
    public BigDecimal price() { return price; }
    public BigDecimal size() { return size; }
    public int leverage() { return leverage; }
    public String tdMode() { return tdMode; }
    public BigDecimal stopLossPrice() { return stopLossPrice; }
    public BigDecimal takeProfitPrice() { return takeProfitPrice; }
    public BigDecimal maxLossAmount() { return maxLossAmount; }
    public BigDecimal marginAmount() { return marginAmount; }
    public UUID budgetReservationId() { return budgetReservationId; }
    public String budgetAllocationJson() { return budgetAllocationJson; }
    public String clientOrderId() { return clientOrderId; }
    public BigDecimal riskRewardRatio() { return riskRewardRatio; }
    public int signalScore() { return signalScore; }
    public BigDecimal fundingRate() { return fundingRate; }
    public BigDecimal volatility() { return volatility; }
    public BigDecimal volume24h() { return volume24h; }
    public UUID tradePlanId() { return tradePlanId; }
    public String confirmToken() { return confirmToken; }
    public OrderStatus status() { return status; }
    public boolean userConfirmed() { return userConfirmed; }
    public Instant confirmedAt() { return confirmedAt; }
    public Instant submittedAt() { return submittedAt; }
    public Instant executedAt() { return executedAt; }
    public String externalOrderId() { return externalOrderId; }
    public String rejectReason() { return rejectReason; }
    public Instant createdAt() { return createdAt; }
    public Instant expireAt() { return expireAt; }

    public boolean isExpired(Instant now) {
        return now.isAfter(expireAt);
    }

    public void markExpired() {
        this.status = OrderStatus.EXPIRED;
        this.rejectReason = "订单已过期";
    }

    public void markConfirmed(Instant now) {
        this.status = OrderStatus.CONFIRMED;
        this.userConfirmed = true;
        this.confirmedAt = now;
    }

    public void applyMarginAmount(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("确认金额必须大于0");
        }
        if (price == null || price.signum() <= 0) {
            throw new IllegalArgumentException("计划入场价无效，不能换算下单数量");
        }
        this.marginAmount = amount.setScale(4, RoundingMode.HALF_UP);
        this.size = amount.multiply(BigDecimal.valueOf(leverage))
                .divide(price, 8, RoundingMode.DOWN);
        this.maxLossAmount = price.subtract(stopLossPrice).abs()
                .multiply(size)
                .setScale(4, RoundingMode.HALF_UP);
    }

    public synchronized void applyBudgetReservation(BigDecimal orderMarginUsdt, UUID reservationId,
                                                    String allocationJson, String clientOrderId) {
        applyMarginAmount(orderMarginUsdt);
        this.budgetReservationId = reservationId;
        this.budgetAllocationJson = allocationJson;
        this.clientOrderId = clientOrderId;
        this.status = OrderStatus.BUDGET_RESERVED;
    }

    public synchronized boolean transition(OrderStatus expectedStatus, OrderStatus newStatus) {
        if (this.status != expectedStatus) {
            return false;
        }
        this.status = newStatus;
        return true;
    }

    public synchronized boolean transitionFromAny(OrderStatus newStatus, OrderStatus... expectedStatuses) {
        for (OrderStatus expectedStatus : expectedStatuses) {
            if (this.status == expectedStatus) {
                this.status = newStatus;
                return true;
            }
        }
        return false;
    }

    public void adjustLeverage(int leverage) {
        if (leverage <= 0) {
            throw new IllegalArgumentException("杠杆必须大于0");
        }
        this.leverage = leverage;
        if (marginAmount != null && marginAmount.signum() > 0) {
            applyMarginAmount(marginAmount);
        }
    }

    public void markExecuted(Instant now) {
        this.status = OrderStatus.EXECUTED;
        this.executedAt = now;
    }

    public void markSubmitted(Instant now, String externalOrderId) {
        this.status = OrderStatus.SUBMITTED;
        this.submittedAt = now;
        this.externalOrderId = externalOrderId;
    }

    public void markUnknownSubmitStatus(String reason) {
        this.status = OrderStatus.UNKNOWN_SUBMIT_STATUS;
        this.rejectReason = reason;
    }

    public void markProtectionFailed(String reason) {
        this.status = OrderStatus.PROTECTION_FAILED;
        this.rejectReason = reason;
    }

    public void markRejected(String reason) {
        this.status = OrderStatus.REJECTED;
        this.rejectReason = reason;
    }

    public void markRetryableRejected(String reason) {
        this.status = OrderStatus.PENDING_CONFIRM;
        this.userConfirmed = false;
        this.confirmedAt = null;
        this.rejectReason = reason;
    }

    public void markCancelled(String reason) {
        this.status = OrderStatus.CANCELLED;
        this.rejectReason = reason;
    }
}
