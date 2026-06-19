package com.example.quant.order;

import com.example.quant.account.AccountSnapshotService;
import com.example.quant.account.dto.AccountSummary;
import com.example.quant.agent.budget.AutoTradeBudgetService;
import com.example.quant.agent.market.OrderBookLiquidityService;
import com.example.quant.agent.market.OrderBookLiquiditySnapshot;
import com.example.quant.agent.plan.TradePlanRecordService;
import com.example.quant.config.AgentProperties;
import com.example.quant.okxtrade.OkxTradeAdapter;
import com.example.quant.risk.ContractRiskRequest;
import com.example.quant.risk.RiskCheckResult;
import com.example.quant.risk.RiskService;
import com.example.quant.system.SystemControlService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class OrderConfirmService {
    private static final Logger log = LoggerFactory.getLogger(OrderConfirmService.class);

    private final PendingOrderService pendingOrderService;
    private final RiskService riskService;
    private final OkxTradeAdapter okxTradeAdapter;
    private final OrderBookLiquidityService orderBookLiquidityService;
    private final AgentProperties agentProperties;
    private final AccountSnapshotService accountSnapshotService;
    private final SystemControlService systemControlService;
    private final TradePlanRecordService tradePlanRecordService;
    private final AutoTradeBudgetService budgetService;
    private final Clock clock;

    @Autowired
    public OrderConfirmService(PendingOrderService pendingOrderService, RiskService riskService,
                               OkxTradeAdapter okxTradeAdapter,
                               OrderBookLiquidityService orderBookLiquidityService,
                               AgentProperties agentProperties,
                               AccountSnapshotService accountSnapshotService,
                               SystemControlService systemControlService,
                               TradePlanRecordService tradePlanRecordService,
                               AutoTradeBudgetService budgetService) {
        this(pendingOrderService, riskService, okxTradeAdapter, orderBookLiquidityService,
                agentProperties, accountSnapshotService, systemControlService, tradePlanRecordService, budgetService, Clock.systemUTC());
    }

    public OrderConfirmService(PendingOrderService pendingOrderService, RiskService riskService, OkxTradeAdapter okxTradeAdapter) {
        this(pendingOrderService, riskService, okxTradeAdapter, null, null, null, null, null, null, Clock.systemUTC());
    }

    public OrderConfirmService(PendingOrderService pendingOrderService, RiskService riskService,
                               OkxTradeAdapter okxTradeAdapter, Clock clock) {
        this(pendingOrderService, riskService, okxTradeAdapter, null, null, null, null, null, null, clock);
    }

    public OrderConfirmService(PendingOrderService pendingOrderService, RiskService riskService,
                               OkxTradeAdapter okxTradeAdapter, OrderBookLiquidityService orderBookLiquidityService,
                               Clock clock) {
        this(pendingOrderService, riskService, okxTradeAdapter, orderBookLiquidityService, null, null, null, null, null, clock);
    }

    public OrderConfirmService(PendingOrderService pendingOrderService, RiskService riskService,
                               OkxTradeAdapter okxTradeAdapter, OrderBookLiquidityService orderBookLiquidityService,
                               AgentProperties agentProperties, AccountSnapshotService accountSnapshotService,
                               SystemControlService systemControlService, TradePlanRecordService tradePlanRecordService,
                               Clock clock) {
        this(pendingOrderService, riskService, okxTradeAdapter, orderBookLiquidityService,
                agentProperties, accountSnapshotService, systemControlService, tradePlanRecordService, null, clock);
    }

    public OrderConfirmService(PendingOrderService pendingOrderService, RiskService riskService,
                               OkxTradeAdapter okxTradeAdapter, OrderBookLiquidityService orderBookLiquidityService,
                               AgentProperties agentProperties, AccountSnapshotService accountSnapshotService,
                               SystemControlService systemControlService, TradePlanRecordService tradePlanRecordService,
                               AutoTradeBudgetService budgetService,
                               Clock clock) {
        this.pendingOrderService = pendingOrderService;
        this.riskService = riskService;
        this.okxTradeAdapter = okxTradeAdapter;
        this.orderBookLiquidityService = orderBookLiquidityService;
        this.agentProperties = agentProperties;
        this.accountSnapshotService = accountSnapshotService;
        this.systemControlService = systemControlService;
        this.tradePlanRecordService = tradePlanRecordService;
        this.budgetService = budgetService;
        this.clock = clock;
    }

    public OrderExecutionResult confirm(UUID orderId, BigDecimal marginAmount) {
        return confirm(orderId, marginAmount, null, false);
    }

    public OrderExecutionResult confirmAuto(UUID orderId, BigDecimal marginAmount) {
        return confirm(orderId, marginAmount, null, true);
    }

    public OrderExecutionResult confirm(UUID orderId, BigDecimal marginAmount, ContractRiskRequest riskRequest) {
        return confirm(orderId, marginAmount, riskRequest, false);
    }

    private OrderExecutionResult confirm(UUID orderId, BigDecimal marginAmount, ContractRiskRequest providedRiskRequest,
                                         boolean autoFixedMargin) {
        PendingOrder order = pendingOrderService.get(orderId);
        Instant now = clock.instant();
        log.info("Confirm order requested id={} instId={} status={} marginAmount={}",
                order.id(), order.instId(), order.status(), marginAmount);
        if (!confirmable(order, autoFixedMargin)) {
            log.warn("Confirm order rejected id={} instId={} reason=status_not_allowed status={}",
                    order.id(), order.instId(), order.status());
            return OrderExecutionResult.rejected("订单状态不允许确认");
        }
        if (order.isExpired(now)) {
            order.markExpired();
            log.warn("Confirm order rejected id={} instId={} reason=expired expireAt={} now={}",
                    order.id(), order.instId(), order.expireAt(), now);
            releaseBudget(order, "ORDER_EXPIRED");
            return OrderExecutionResult.rejected("待确认订单已过期");
        }
        if (!order.transitionFromAny(OrderStatus.CONFIRMING, OrderStatus.PENDING_CONFIRM, OrderStatus.BUDGET_RESERVED)) {
            log.warn("Confirm order rejected id={} instId={} reason=confirming_or_submitted status={}",
                    order.id(), order.instId(), order.status());
            return OrderExecutionResult.rejected("订单状态不允许重复确认");
        }
        BigDecimal effectiveMargin = autoFixedMargin && order.marginAmount() != null && order.marginAmount().signum() > 0
                ? order.marginAmount()
                : marginAmount;
        if (autoFixedMargin && order.budgetReservationId() != null
                && budgetService != null
                && !budgetService.isReserved(order.budgetReservationId(), order.id())) {
            order.markRejected("自动交易预算未处于RESERVED状态");
            log.warn("Confirm order rejected id={} instId={} reason=budget_not_reserved reservationId={}",
                    order.id(), order.instId(), order.budgetReservationId());
            return OrderExecutionResult.rejected("自动交易预算未预占用或已释放");
        }
        try {
            order.applyMarginAmount(effectiveMargin);
            applyAutoTradeMinimumLeverage(order, autoFixedMargin);
        } catch (IllegalArgumentException ex) {
            log.warn("Confirm order rejected id={} instId={} reason={}", order.id(), order.instId(), ex.getMessage());
            releaseBudget(order, "MARGIN_INVALID");
            return OrderExecutionResult.rejected(ex.getMessage());
        }
        boolean noRiskAutoMode = noRiskAutoMode(autoFixedMargin);
        if (noRiskAutoMode && systemControlService != null && systemControlService.emergencyStopEnabled()) {
            String reason = "紧急停止状态下不能自动提交订单";
            order.markRejected(reason);
            releaseBudget(order, "EMERGENCY_STOP");
            log.warn("Confirm order rejected id={} instId={} reason=emergency_stop_no_risk_auto", order.id(), order.instId());
            return OrderExecutionResult.rejected(reason);
        }
        OrderBookLiquiditySnapshot liquidity = currentLiquidity(order);
        String liquidityDenyReason = hardLiquidityDenyReason(liquidity);
        if (!liquidityDenyReason.isBlank()) {
            String reason = "实时订单簿流动性不足：" + liquidityDenyReason;
            rejectBeforeSubmit(order, reason, autoFixedMargin, "LIQUIDITY_REJECTED");
            log.warn("Confirm order rejected by live liquidity id={} instId={} spreadBps={} bidDepth={} askDepth={} reason={}",
                    order.id(), order.instId(), liquidity.spreadBps(), liquidity.bidDepthUsdt(),
                    liquidity.askDepthUsdt(), liquidityDenyReason);
            return OrderExecutionResult.rejected(reason);
        }
        if (noRiskAutoMode) {
            log.warn("No-risk auto confirmation bypassed live liquidity and rule risk checks id={} instId={} spreadBps={}",
                    order.id(), order.instId(), liquidity == null ? null : liquidity.spreadBps());
        } else {
            adjustMarginByDepth(order, liquidity, autoFixedMargin);
            ContractRiskRequest riskRequest = providedRiskRequest == null
                    ? riskRequestFor(order, order.marginAmount(), autoFixedMargin)
                    : providedRiskRequest.withSuggestedMargin(order.marginAmount());
            RiskCheckResult risk = riskService.check(riskRequest);
            if (!risk.passed() && autoFixedMargin && canRetryWithLowerLeverage(order, risk)) {
                int adjustedLeverage = risk.adjustedLeverage();
                order.adjustLeverage(adjustedLeverage);
                log.warn("Auto confirm lowered leverage by dynamic risk cap id={} instId={} adjustedLeverage={} marginAmount={} size={}",
                        order.id(), order.instId(), adjustedLeverage, order.marginAmount(), order.size());
                riskRequest = riskRequestFor(order, order.marginAmount(), true);
                risk = riskService.check(riskRequest);
            }
            if (!risk.passed()) {
                rejectBeforeSubmit(order, risk.rejectReason(), autoFixedMargin, "RISK_REJECTED");
                log.warn("Confirm order rejected by risk id={} instId={} reason={} riskLevel={}",
                        order.id(), order.instId(), risk.rejectReason(), risk.riskLevel());
                return OrderExecutionResult.rejected(risk.rejectReason());
            }
        }
        order.markConfirmed(now);
        order.transition(OrderStatus.CONFIRMED, OrderStatus.SUBMITTING);
        log.info("Risk passed, submitting OKX order id={} instId={} side={} posSide={} ordType={} tdMode={} size={} px={}",
                order.id(), order.instId(), order.side(), order.posSide(), order.orderType(), order.tdMode(),
                order.size(), order.price());
        try {
            OrderExecutionResult result = okxTradeAdapter.placeOrder(order);
            if (result.submitted()) {
                order.markSubmitted(clock.instant(), result.externalOrderId());
                if (result.filled()) {
                    markBudgetUsed(order);
                }
                if (tradePlanRecordService != null && order.tradePlanId() != null) {
                    tradePlanRecordService.markOrderSubmitted(order.tradePlanId(), result.externalOrderId());
                }
                log.info("OKX order submitted id={} instId={} externalOrderId={} message={}",
                        order.id(), order.instId(), result.externalOrderId(), result.message());
            } else {
                order.markRejected(result.message());
                releaseBudget(order, "OKX_REJECTED");
                log.warn("OKX order was not submitted id={} instId={} message={}",
                        order.id(), order.instId(), result.message());
            }
            return result;
        } catch (RuntimeException ex) {
            if (isTimeout(ex)) {
                order.markUnknownSubmitStatus(ex.getMessage());
                throw new OrderSubmitStatusUnknownException(ex.getMessage(), ex);
            } else {
                order.markRejected(ex.getMessage());
                releaseBudget(order, "OKX_SUBMIT_FAILED");
            }
            log.error("OKX order submission failed id={} instId={} message={}",
                    order.id(), order.instId(), ex.getMessage(), ex);
            throw ex;
        }
    }

    private static boolean canRetryWithLowerLeverage(PendingOrder order, RiskCheckResult risk) {
        return "LEVERAGE_ABOVE_DYNAMIC_CAP".equals(risk.rejectCode())
                && risk.adjustedLeverage() != null
                && risk.adjustedLeverage() > 0
                && risk.adjustedLeverage() < order.leverage();
    }

    private static boolean confirmable(PendingOrder order, boolean autoFixedMargin) {
        if (autoFixedMargin) {
            return order.status() == OrderStatus.PENDING_CONFIRM || order.status() == OrderStatus.BUDGET_RESERVED;
        }
        return order.status() == OrderStatus.PENDING_CONFIRM;
    }

    private boolean noRiskAutoMode(boolean autoFixedMargin) {
        return autoFixedMargin
                && systemControlService != null
                && systemControlService.autoTradeRiskMode() != null
                && systemControlService.autoTradeRiskMode().noRisk();
    }

    private void applyAutoTradeMinimumLeverage(PendingOrder order, boolean autoFixedMargin) {
        if (!autoFixedMargin || systemControlService == null) {
            return;
        }
        int minLeverage = systemControlService.autoTradeMinLeverage();
        if (minLeverage <= 0 || order.leverage() >= minLeverage) {
            return;
        }
        int before = order.leverage();
        order.adjustLeverage(minLeverage);
        log.warn("Auto confirm raised leverage by user minimum id={} instId={} from={} to={} marginAmount={} size={}",
                order.id(), order.instId(), before, minLeverage, order.marginAmount(), order.size());
    }

    private void rejectBeforeSubmit(PendingOrder order, String reason, boolean autoFixedMargin, String budgetReleaseReason) {
        if (autoFixedMargin) {
            order.markRejected(reason);
            releaseBudget(order, budgetReleaseReason);
            return;
        }
        order.markRetryableRejected(reason);
    }

    private void releaseBudget(PendingOrder order, String reason) {
        if (budgetService != null && order.budgetReservationId() != null) {
            budgetService.release(order.budgetReservationId(), reason);
        }
    }

    private void markBudgetUsed(PendingOrder order) {
        if (budgetService != null && order.budgetReservationId() != null) {
            budgetService.markUsed(order.budgetReservationId());
        }
    }

    private static boolean isTimeout(RuntimeException ex) {
        String message = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase();
        return message.contains("timeout") || message.contains("timed out") || message.contains("超时");
    }

    private ContractRiskRequest riskRequestFor(PendingOrder order, BigDecimal marginAmount, boolean autoFixedMargin) {
        ContractRiskRequest defaults = ContractRiskRequest.safeDefaults();
        AccountSummary account = accountSnapshot();
        BigDecimal accountEquity = account.equity().signum() > 0
                ? account.equity()
                : account.availableBalance().signum() > 0
                ? account.availableBalance()
                : defaults.accountEquity();
        BigDecimal maxSingleMarginRate = autoFixedMargin
                ? BigDecimal.ONE
                : agentProperties == null
                ? defaults.maxSingleMarginRate()
                : percentRate(agentProperties.risk().maxSingleLossPercent());
        BigDecimal maxSingleLossRate = agentProperties == null
                ? defaults.maxSingleLossRate()
                : percentRate(agentProperties.risk().singleTradeRiskPercent());
        int maxLeverage = agentProperties == null ? defaults.maxLeverage() : agentProperties.risk().maxLeverage();
        BigDecimal maxFunding = agentProperties == null
                ? defaults.maxAbsFundingRate()
                : autoFixedMargin
                ? agentProperties.market().maxFundingAbs().multiply(BigDecimal.valueOf(3))
                : agentProperties.market().maxFundingAbs();
        BigDecimal minVolume = autoFixedMargin
                ? BigDecimal.ZERO
                : agentProperties == null ? defaults.minVolume24h() : agentProperties.market().minVolume24hUsdt();
        int minSignalScore = autoFixedMargin
                ? 0
                : agentProperties == null ? defaults.minSignalScore() : agentProperties.score().minTotalScore();
        return new ContractRiskRequest(
                systemControlService != null && systemControlService.emergencyStopEnabled(),
                defaults.websocketConnected(),
                defaults.marketDelayMs(),
                defaults.maxMarketDelayMs(),
                accountEquity,
                marginAmount,
                maxSingleMarginRate,
                order.leverage(),
                maxLeverage,
                order.price(),
                order.stopLossPrice(),
                order.riskRewardRatio(),
                defaults.minRiskRewardRatio(),
                order.fundingRate(),
                maxFunding,
                order.volume24h(),
                minVolume,
                defaults.newsRiskLevel(),
                order.volatility(),
                defaults.maxStopLossDistanceRate(),
                order.signalScore(),
                minSignalScore,
                maxSingleLossRate
        );
    }

    private AccountSummary accountSnapshot() {
        if (accountSnapshotService == null) {
            return new AccountSummary(BigDecimal.ZERO, BigDecimal.ZERO, "DEFAULT", "");
        }
        return accountSnapshotService.summary();
    }

    private static BigDecimal percentRate(BigDecimal percent) {
        if (percent == null) {
            return BigDecimal.ZERO;
        }
        return percent.divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP);
    }

    private OrderBookLiquiditySnapshot currentLiquidity(PendingOrder order) {
        if (orderBookLiquidityService == null) {
            return null;
        }
        OrderBookLiquiditySnapshot liquidity = orderBookLiquidityService.snapshot(order.instId());
        log.info("Confirm order live liquidity id={} instId={} spreadBps={} bidDepth={} askDepth={} tradable={}",
                order.id(), order.instId(), liquidity.spreadBps(), liquidity.bidDepthUsdt(),
                liquidity.askDepthUsdt(), liquidity.tradable());
        return liquidity;
    }

    private String hardLiquidityDenyReason(OrderBookLiquiditySnapshot liquidity) {
        if (liquidity == null) {
            return "";
        }
        BigDecimal maxSpread = agentProperties == null
                ? BigDecimal.valueOf(8)
                : agentProperties.market().maxSpreadBps();
        if (liquidity.spreadBps().compareTo(maxSpread) > 0) {
            return "spread_bps_above_" + maxSpread.stripTrailingZeros().toPlainString();
        }
        return "";
    }

    private void adjustMarginByDepth(PendingOrder order, OrderBookLiquiditySnapshot liquidity, boolean autoFixedMargin) {
        if (liquidity == null) {
            return;
        }
        BigDecimal availableDepth = liquidity.availableDepthUsdt();
        if (availableDepth.signum() <= 0) {
            log.warn("Confirm order continues with configured margin because live depth is unavailable id={} instId={} reason={}",
                    order.id(), order.instId(), liquidity.denyReason());
            return;
        }
        BigDecimal maxDepthUsageRate = maxDepthUsageRate();
        BigDecimal maxTradableNotional = availableDepth.multiply(maxDepthUsageRate);
        BigDecimal orderNotional = order.marginAmount().multiply(BigDecimal.valueOf(order.leverage()));
        if (orderNotional.compareTo(maxTradableNotional) > 0) {
            if (autoFixedMargin) {
                log.warn("Auto confirm keeps configured margin despite thin live depth id={} instId={} marginAmount={} notional={} availableDepth={} usageRate={}",
                        order.id(), order.instId(), order.marginAmount(), orderNotional, availableDepth,
                        maxDepthUsageRate);
                return;
            }
            BigDecimal adjustedMargin = maxTradableNotional
                    .divide(BigDecimal.valueOf(order.leverage()), 8, RoundingMode.DOWN);
            if (adjustedMargin.signum() > 0 && adjustedMargin.compareTo(order.marginAmount()) < 0) {
                order.applyMarginAmount(adjustedMargin);
                log.warn("Confirm order margin reduced by live depth id={} instId={} adjustedMargin={} availableDepth={} usageRate={}",
                        order.id(), order.instId(), adjustedMargin, availableDepth, maxDepthUsageRate);
            }
        }
    }

    private BigDecimal maxDepthUsageRate() {
        if (agentProperties == null || agentProperties.market().maxDepthUsageRate() == null
                || agentProperties.market().maxDepthUsageRate().signum() <= 0) {
            return new BigDecimal("0.20");
        }
        return agentProperties.market().maxDepthUsageRate();
    }
}
