package com.example.quant.order;

import com.example.quant.account.AccountSnapshotService;
import com.example.quant.account.OkxCredentialStore;
import com.example.quant.account.dto.AccountSummary;
import com.example.quant.agent.budget.AutoTradeBudgetService;
import com.example.quant.agent.market.OrderBookLiquidityService;
import com.example.quant.agent.market.OrderBookLiquiditySnapshot;
import com.example.quant.agent.plan.TradePlanRecordService;
import com.example.quant.auth.AuthUserContext;
import com.example.quant.config.AgentProperties;
import com.example.quant.okxtrade.OkxTradeAdapter;
import com.example.quant.okxtrade.OkxInstrumentRulesProvider;
import com.example.quant.okxtrade.OkxInstrumentRules;
import com.example.quant.okxtrade.OrderNotSubmittedException;
import com.example.quant.okxtrade.OkxApiRejectedException;
import com.example.quant.tradeplan.CanonicalExecutablePlan;
import com.example.quant.tradeplan.CanonicalPlanCompiler;
import com.example.quant.tradeplan.CanonicalPlanShadow;
import com.example.quant.tradeplan.TradeIntent;
import com.example.quant.risk.ContractRiskRequest;
import com.example.quant.risk.DailyRiskService;
import com.example.quant.risk.RiskCheckResult;
import com.example.quant.risk.RiskService;
import com.example.quant.system.SystemControlService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class OrderConfirmService {
    private static final Logger log = LoggerFactory.getLogger(OrderConfirmService.class);

    /**
     * Maximum accepted relative gap between the pre-canonical stop-loss estimate
     * and the canonical one. Tick/scale rounding stays far below this; a real
     * recomputation drift does not.
     */
    private static final BigDecimal CANONICAL_RISK_DRIFT_LIMIT = new BigDecimal("0.10");

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
    private DailyRiskService dailyRiskService;
    private OkxInstrumentRulesProvider instrumentRulesProvider;
    private CanonicalPlanCompiler canonicalPlanCompiler;

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

    @Autowired(required = false)
    void configureDailyRisk(DailyRiskService dailyRiskService) {
        this.dailyRiskService = dailyRiskService;
    }

    @Autowired(required = false)
    void configureInstrumentRules(OkxInstrumentRulesProvider provider) {
        this.instrumentRulesProvider = provider;
    }

    @Autowired(required = false)
    void configureCanonicalPlanCompiler(CanonicalPlanCompiler compiler) {
        this.canonicalPlanCompiler = compiler;
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

    public OrderExecutionResult resolveUnknownSubmit(UUID orderId) {
        PendingOrder order = pendingOrderService.get(orderId);
        if (order.status() != OrderStatus.UNKNOWN_SUBMIT_STATUS) {
            return OrderExecutionResult.rejected("订单不是待恢复的UNKNOWN提交状态");
        }
        if (order.requiresLegacyAccountingReconciliation()) {
            return OrderExecutionResult.unknown("历史受理订单需要先核对预算与每日风险记账，不能仅凭ACK恢复入场");
        }
        OrderExecutionResult recovered = okxTradeAdapter.recoverUnknownSubmitStatus(order);
        if (recovered.submitted()) {
            order.markSubmitted(clock.instant(), recovered.externalOrderId());
            return recovered;
        }
        if (recovered.message() != null
                && recovered.message().contains("OKX_CLORDID_NOT_FOUND_AFTER_TIMEOUT")) {
            String owner = AuthUserContext.currentUsername().orElse(OkxCredentialStore.SYSTEM_USER);
            order.markRejected(recovered.message() + "; EXPLICIT_EXCHANGE_NOT_FOUND_RESOLUTION");
            releaseBudget(order, "UNKNOWN_SUBMIT_EXCHANGE_NOT_FOUND");
            releaseDailyRiskClaim(owner, order);
            return OrderExecutionResult.rejected(order.rejectReason());
        }
        return OrderExecutionResult.unknown(recovered.message());
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
        OrderExecutionResult stopped = rejectStoppedAutoTrade(order, autoFixedMargin);
        if (stopped != null) {
            return stopped;
        }
        if (noRiskAutoMode(autoFixedMargin)) {
            String reason = "Shadow 模式禁止创建或提交自动订单";
            order.markRejected(reason);
            releaseBudget(order, "SHADOW_MODE");
            log.warn("Confirm order rejected id={} instId={} reason=shadow_mode_no_exchange_write",
                    order.id(), order.instId());
            return OrderExecutionResult.rejected(reason);
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
            normalizeExecutableQuantity(order);
        } catch (IllegalArgumentException ex) {
            log.warn("Confirm order rejected id={} instId={} reason={}", order.id(), order.instId(), ex.getMessage());
            releaseBudget(order, "MARGIN_INVALID");
            return OrderExecutionResult.rejected(ex.getMessage());
        }
        OrderBookLiquiditySnapshot liquidity = currentLiquidity(order);
        String liquidityDenyReason = hardLiquidityDenyReason(order, liquidity, autoFixedMargin);
        if (!liquidityDenyReason.isBlank()) {
            String reason = "实时订单簿流动性不足：" + liquidityDenyReason;
            rejectBeforeSubmit(order, reason, autoFixedMargin, "LIQUIDITY_REJECTED");
            log.warn("Confirm order rejected by live liquidity id={} instId={} spreadBps={} bidDepth={} askDepth={} reason={}",
                    order.id(), order.instId(), liquidity.spreadBps(), liquidity.bidDepthUsdt(),
                    liquidity.askDepthUsdt(), liquidityDenyReason);
            return OrderExecutionResult.rejected(reason);
        }
        if (!autoFixedMargin) {
            adjustMarginByDepth(order, liquidity);
        }
        ContractRiskRequest riskRequest = providedRiskRequest == null
                ? riskRequestFor(order, order.marginAmount(), liquidity, autoFixedMargin)
                : providedRiskRequest.withSuggestedMargin(order.marginAmount());
        RiskCheckResult risk = riskService.check(riskRequest);
        if (!risk.passed() && autoFixedMargin && canRetryWithLowerLeverage(order, risk)) {
            int adjustedLeverage = risk.adjustedLeverage();
            order.adjustLeverage(adjustedLeverage);
            log.warn("Auto confirm lowered leverage by dynamic risk cap id={} instId={} adjustedLeverage={} marginAmount={} size={}",
                    order.id(), order.instId(), adjustedLeverage, order.marginAmount(), order.size());
            riskRequest = riskRequestFor(order, order.marginAmount(), liquidity, true);
            risk = riskService.check(riskRequest);
        }
        if (!risk.passed()) {
            rejectBeforeSubmit(order, risk.rejectReason(), autoFixedMargin, "RISK_REJECTED");
            log.warn("Confirm order rejected by risk id={} instId={} reason={} riskLevel={}",
                    order.id(), order.instId(), risk.rejectReason(), risk.riskLevel());
            return OrderExecutionResult.rejected(risk.rejectReason());
        }
        OrderExecutionResult canonicalResult = compileAndBindCanonicalPlan(order);
        if (canonicalResult != null) {
            rejectBeforeSubmit(order, canonicalResult.message(), autoFixedMargin, "CANONICAL_PLAN_REJECTED");
            log.warn("Confirm order rejected by canonical plan compiler id={} instId={} reason={}",
                    order.id(), order.instId(), canonicalResult.message());
            return canonicalResult;
        }
        stopped = rejectStoppedAutoTrade(order, autoFixedMargin);
        if (stopped != null) {
            return stopped;
        }
        String owner = AuthUserContext.currentUsername().orElse(OkxCredentialStore.SYSTEM_USER);
        if (dailyRiskService != null) {
            DailyRiskService.Decision dailyRisk;
            try {
                dailyRisk = dailyRiskService.claimEntry(owner, order.id().toString(), riskRequest.accountEquity(), now);
            } catch (RuntimeException ex) {
                dailyRisk = new DailyRiskService.Decision(false, "DAILY_RISK_STATE_UNAVAILABLE");
            }
            if (!dailyRisk.allowed()) {
                rejectBeforeSubmit(order, dailyRisk.reason(), autoFixedMargin, "DAILY_RISK_REJECTED");
                log.warn("Confirm order rejected by daily risk id={} instId={} reason={}",
                        order.id(), order.instId(), dailyRisk.reason());
                return OrderExecutionResult.rejected(dailyRisk.reason());
            }
        }
        order.markConfirmed(now);
        order.transition(OrderStatus.CONFIRMED, OrderStatus.SUBMITTING);
        log.info("Risk passed, submitting OKX order id={} instId={} side={} posSide={} ordType={} tdMode={} size={} px={}",
                order.id(), order.instId(), order.side(), order.posSide(), order.orderType(), order.tdMode(),
                order.size(), order.price());
        OrderExecutionResult acceptedResult = null;
        try {
            OrderExecutionResult result = okxTradeAdapter.placeOrder(order);
            if (result.submitted()) acceptedResult = result;
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
            } else if (result.unknown()) {
                throw new OrderSubmitStatusUnknownException(result.message());
            } else {
                order.markRejected(result.message());
                releaseBudget(order, "OKX_REJECTED");
                releaseDailyRiskClaim(owner, order);
                log.warn("OKX order was not submitted id={} instId={} message={}",
                        order.id(), order.instId(), result.message());
            }
            return result;
        } catch (RuntimeException ex) {
            if (acceptedResult != null) {
                // markSubmitted assigns ACK facts before its persistence callback.
                // A failed callback leaves durable SUBMITTING to reconcile on restart.
                log.error("OKX_ACK_LOCAL_PERSISTENCE_FAILED id={} instId={} ordId={}",
                        order.id(), order.instId(), acceptedResult.externalOrderId(), ex);
                return acceptedResult;
            }
            if (ex instanceof OrderNotSubmittedException || ex instanceof OkxApiRejectedException) {
                order.markRejected(ex.getMessage());
                releaseBudget(order, ex instanceof OrderNotSubmittedException ? "PRE_ORDER_SETUP_FAILED" : "OKX_REJECTED");
                releaseDailyRiskClaim(owner, order);
                log.error("OKX order definitively failed id={} instId={}", order.id(), order.instId(), ex);
                throw ex;
            }
            try {
                order.markUnknownSubmitStatus(ex.getMessage());
            } catch (RuntimeException persistenceFailure) {
                ex.addSuppressed(persistenceFailure);
            }
            // Do not release the reservation or risk claim after an uncertain send.
            throw new OrderSubmitStatusUnknownException(ex.getMessage(), ex);
        }
    }

    private void normalizeExecutableQuantity(PendingOrder order) {
        if (instrumentRulesProvider == null || order.size() == null || order.price() == null) return;
        OkxInstrumentRules rules = instrumentRulesProvider.rules(order.instId());
        order.captureIntendedBaseQuantity();
        BigDecimal contracts = rules.normalizeEntrySize(order.size(), order.price());
        if (contracts.signum() <= 0) throw new IllegalArgumentException("最终合约数量低于最小下单量");
        BigDecimal base = rules.contractValueInQuoteCurrencyForAudit()
                ? contracts.multiply(rules.contractValue()).divide(order.price(), 16, RoundingMode.DOWN)
                : contracts.multiply(rules.contractValue());
        order.replaceExecutableSize(base);
        order.setExecutableContractQuantity(contracts);
    }

    private OrderExecutionResult compileAndBindCanonicalPlan(PendingOrder order) {
        if (canonicalPlanCompiler == null) {
            return null;
        }
        if (instrumentRulesProvider == null) {
            return OrderExecutionResult.rejected("CANONICAL_PLAN_RULES_UNAVAILABLE");
        }
        try {
            OkxInstrumentRules rules = instrumentRulesProvider.rules(order.instId());
            AgentProperties configured = agentProperties == null ? new AgentProperties() : agentProperties;
            TradeIntent intent = TradeIntent.fromPendingOrder(
                    order,
                    configured.takeProfit().tp1Ratio(),
                    configured.takeProfit().tp2Ratio(),
                    configured.takeProfit().tp3Ratio(),
                    "trade-plan-v1");
            CanonicalExecutablePlan compiled = canonicalPlanCompiler.compile(intent, rules);
            log.info("Canonical plan shadow compile id={} instId={} status={} divergenceTypes={}",
                    order.id(), order.instId(), compiled.status(), CanonicalPlanShadow.divergences(order, compiled));
            order.setCanonicalPlanSnapshot(compiled);
            if (tradePlanRecordService != null && order.tradePlanId() != null) {
                tradePlanRecordService.recordCanonicalPlan(order.tradePlanId(), compiled, order.canonicalPlanJson());
            }
            if (!compiled.accepted()) {
                return OrderExecutionResult.rejected("CANONICAL_PLAN_REJECTED: "
                        + String.join(",", compiled.decisions()));
            }
            if (order.executableContractQuantity() == null
                    || order.executableContractQuantity().compareTo(compiled.entry().executableContractQuantity()) != 0) {
                return OrderExecutionResult.rejected("CANONICAL_PLAN_RISK_MISMATCH: executable quantity differs");
            }
            BigDecimal canonicalStopLossAmount = compiled.risk().executableStopLossAmount();
            if (canonicalStopLossAmount == null) {
                return OrderExecutionResult.rejected("CANONICAL_PLAN_RISK_MISSING");
            }
            // The canonical plan is the single executable truth, so adopt its
            // risk figure. The legacy estimate computed in PendingOrder is a
            // pre-canonical approximation built from raw (non tick-normalised)
            // prices at scale 4, while the compiler uses tick-normalised prices
            // at scale 8; on low-priced contracts that gap is a rounding
            // artifact of tick-sized magnitude, not a change of risk. A genuine
            // recomputation drift is still rejected via the relative bound.
            if (order.executableStopLossAmount() != null) {
                BigDecimal baseline = order.executableStopLossAmount();
                BigDecimal drift = baseline.signum() == 0
                        ? canonicalStopLossAmount.abs()
                        : canonicalStopLossAmount.subtract(baseline).abs()
                                .divide(baseline, 8, RoundingMode.HALF_UP);
                if (drift.compareTo(CANONICAL_RISK_DRIFT_LIMIT) > 0) {
                    log.warn("Canonical plan risk drift rejected id={} instId={} legacy={} canonical={} drift={}",
                            order.id(), order.instId(), baseline, canonicalStopLossAmount, drift);
                    return OrderExecutionResult.rejected("CANONICAL_PLAN_RISK_MISMATCH: executable stop loss drift "
                            + drift.toPlainString());
                }
                if (drift.signum() > 0) {
                    log.info("Canonical plan adopted executable stop loss id={} instId={} legacy={} canonical={} drift={}",
                            order.id(), order.instId(), baseline, canonicalStopLossAmount, drift);
                }
            }
            order.setExecutableStopLossAmount(canonicalStopLossAmount);
            return null;
        } catch (RuntimeException ex) {
            return OrderExecutionResult.rejected("CANONICAL_PLAN_COMPILATION_FAILED: " + ex.getMessage());
        }
    }

    private void releaseDailyRiskClaim(String owner, PendingOrder order) {
        if (dailyRiskService != null) {
            dailyRiskService.releaseDefinitiveFailure(owner, order.id().toString(), clock.instant());
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

    private OrderExecutionResult rejectStoppedAutoTrade(PendingOrder order, boolean autoFixedMargin) {
        if (!autoFixedMargin || systemControlService == null || systemControlService.autoTradeEnabled()) {
            return null;
        }
        String reason = systemControlService.emergencyStopEnabled()
                ? "紧急停止已开启，自动订单未提交"
                : "自动交易已停止，订单未提交";
        order.markRejected(reason);
        releaseBudget(order, systemControlService.emergencyStopEnabled() ? "EMERGENCY_STOP" : "AUTO_TRADE_STOPPED");
        log.warn("Confirm order rejected id={} instId={} reason=auto_trade_runtime_switch_off",
                order.id(), order.instId());
        return OrderExecutionResult.rejected(reason);
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

    private ContractRiskRequest riskRequestFor(PendingOrder order, BigDecimal marginAmount,
                                               OrderBookLiquiditySnapshot liquidity, boolean autoPipelineValidated) {
        ContractRiskRequest defaults = ContractRiskRequest.safeDefaults();
        boolean measuredMarketData = orderBookLiquidityService == null
                ? defaults.websocketConnected()
                : liquidity != null && liquidity.createdAt() != null;
        long marketDelayMs = orderBookLiquidityService == null
                ? defaults.marketDelayMs()
                : measuredMarketData
                ? Math.max(0, Duration.between(liquidity.createdAt(), clock.instant()).toMillis())
                : Long.MAX_VALUE;
        AccountSummary account = accountSnapshot();
        BigDecimal accountEquity = account.equity().signum() > 0
                ? account.equity()
                : account.availableBalance().signum() > 0
                ? account.availableBalance()
                : defaults.accountEquity();
        BigDecimal maxSingleMarginRate = agentProperties == null
                ? defaults.maxSingleMarginRate()
                : percentRate(agentProperties.risk().maxSingleMarginPercent());
        BigDecimal maxSingleLossRate = agentProperties == null
                ? defaults.maxSingleLossRate()
                : percentRate(agentProperties.risk().singleTradeRiskPercent());
        int maxLeverage = agentProperties == null ? defaults.maxLeverage() : agentProperties.risk().maxLeverage();
        BigDecimal maxFunding = agentProperties == null
                ? defaults.maxAbsFundingRate()
                : agentProperties.market().maxFundingAbs();
        BigDecimal minVolume = agentProperties == null
                ? defaults.minVolume24h()
                : agentProperties.market().minVolume24hUsdt();
        int minSignalScore = agentProperties == null
                ? defaults.minSignalScore()
                : autoPipelineValidated ? 0 : agentProperties.score().minTotalScore();
        return new ContractRiskRequest(
                systemControlService != null && systemControlService.emergencyStopEnabled(),
                measuredMarketData,
                marketDelayMs,
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

    private String hardLiquidityDenyReason(PendingOrder order, OrderBookLiquiditySnapshot liquidity,
                                           boolean autoFixedMargin) {
        if (liquidity == null) {
            return "";
        }
        if (!liquidity.tradable()) {
            return liquidity.denyReason() != null && !liquidity.denyReason().isBlank()
                    ? liquidity.denyReason()
                    : "order_book_unavailable";
        }
        BigDecimal maxSpread = agentProperties == null
                ? BigDecimal.valueOf(8)
                : agentProperties.market().maxSpreadBps();
        if (liquidity.spreadBps().compareTo(maxSpread) > 0) {
            return "spread_bps_above_" + maxSpread.stripTrailingZeros().toPlainString();
        }
        if (autoFixedMargin && "MARKET".equalsIgnoreCase(order.orderType())) {
            if (order.price() == null || order.price().signum() <= 0
                    || liquidity.midPrice() == null || liquidity.midPrice().signum() <= 0) {
                return "market_price_unavailable";
            }
            BigDecimal maxDeviationPct = agentProperties == null
                    ? new BigDecimal("0.25")
                    : agentProperties.preConfirmRefresh().maxPriceDeviationPct();
            BigDecimal deviationPct = order.price().subtract(liquidity.midPrice()).abs()
                    .multiply(BigDecimal.valueOf(100))
                    .divide(liquidity.midPrice(), 8, RoundingMode.HALF_UP);
            if (maxDeviationPct == null || maxDeviationPct.signum() < 0
                    || deviationPct.compareTo(maxDeviationPct) > 0) {
                return "market_price_deviation_above_"
                        + (maxDeviationPct == null ? "unset" : maxDeviationPct.stripTrailingZeros().toPlainString());
            }
        }
        if (autoFixedMargin && liquidity.availableDepthUsdt().signum() > 0) {
            BigDecimal maxTradableNotional = liquidity.availableDepthUsdt().multiply(maxDepthUsageRate());
            BigDecimal orderNotional = order.marginAmount().multiply(BigDecimal.valueOf(order.leverage()));
            if (orderNotional.compareTo(maxTradableNotional) > 0) {
                return "depth_usage_above_" + maxDepthUsageRate().stripTrailingZeros().toPlainString();
            }
        }
        return "";
    }

    private void adjustMarginByDepth(PendingOrder order, OrderBookLiquiditySnapshot liquidity) {
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
