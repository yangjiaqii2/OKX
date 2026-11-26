package com.example.quant.tradeplan;

import com.example.quant.agent.exit.NetProfitExitPolicy;
import com.example.quant.agent.market.OrderBookLiquidityService;
import com.example.quant.agent.market.OrderBookLiquiditySnapshot;
import com.example.quant.config.AgentProperties;
import com.example.quant.okxtrade.OkxInstrumentRules;
import com.example.quant.okxtrade.OkxInstrumentRulesProvider;
import com.example.quant.risk.ContractRiskRequest;
import com.example.quant.risk.RiskService;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Service;

@Service
public class PlanExecutionRisk {
    private static final MathContext PRECISION = MathContext.DECIMAL128;
    private final CanonicalPlanCompiler compiler;
    private final OkxInstrumentRulesProvider instruments;
    private final OrderBookLiquidityService liquidity;
    private final NetProfitExitPolicy feePolicy;
    private final AgentProperties properties;
    private final RiskService riskService;

    public PlanExecutionRisk(CanonicalPlanCompiler compiler, OkxInstrumentRulesProvider instruments,
                             OrderBookLiquidityService liquidity, NetProfitExitPolicy feePolicy,
                             AgentProperties properties, RiskService riskService) {
        this.compiler = compiler;
        this.instruments = instruments;
        this.liquidity = liquidity;
        this.feePolicy = feePolicy;
        this.properties = properties;
        this.riskService = riskService;
    }

    public record Limits(BigDecimal riskBudget, BigDecimal maxMargin, BigDecimal availableBalance,
                         int leverage, BigDecimal minimumRatio, ContractRiskRequest riskRequest) {}

    public record Estimate(CanonicalExecutablePlan executable, BigDecimal baseQuantity,
                           BigDecimal margin, String denial) {
        static Estimate rejected(String reason) {
            return new Estimate(null, null, null, reason);
        }

        public boolean accepted() {
            return denial == null;
        }
    }

    public Estimate estimate(TradeIntent intent, Limits limits, boolean automatic) {
        OkxInstrumentRules rules;
        NetProfitExitPolicy.FeeRates fees;
        try {
            rules = instruments.rules(intent.instId());
            fees = feePolicy.feeRates(intent.instId());
        } catch (RuntimeException ex) {
            return Estimate.rejected("EXECUTION_RULES_OR_FEES_UNAVAILABLE");
        }
        try {
            boolean market = "MARKET".equalsIgnoreCase(intent.entryPolicy());
            OrderBookLiquiditySnapshot quote = market ? liquidity.snapshot(intent.instId()) : null;
            return evaluate(intent, rules, fees, quote, limits, automatic, Instant.now());
        } catch (RuntimeException ex) {
            return Estimate.rejected("EXECUTION_RISK_CALCULATION_FAILED");
        }
    }

    Estimate evaluate(TradeIntent draft, OkxInstrumentRules rules, NetProfitExitPolicy.FeeRates fees,
                      OrderBookLiquiditySnapshot quote, Limits limits, boolean automatic, Instant now) {
        BigDecimal riskBudget = limits.riskBudget();
        if (riskBudget == null || riskBudget.signum() <= 0) {
            return Estimate.rejected("EXECUTION_RISK_BUDGET_UNAVAILABLE");
        }
        if (limits.availableBalance() == null || limits.availableBalance().signum() <= 0) {
            return Estimate.rejected("EXECUTION_AVAILABLE_BALANCE_UNAVAILABLE");
        }
        boolean netTargets = automatic && properties.lifecycle().isNetProfitExitEnabled();
        TradeIntent intent = netTargets ? NetProfitExitPolicy.plan(draft) : draft;
        CanonicalExecutablePlan compiled = compiler.compileAutomaticEntry(
                intent.withSizing(intent.intendedBaseQuantity(), riskBudget), rules);
        if (!compiled.accepted()) {
            return Estimate.rejected("CANONICAL_PLAN_REJECTED: " + String.join(",", compiled.decisions()));
        }
        boolean shortSide = intent.action() == TradePlanType.OPEN_SHORT;
        BigDecimal reference = compiled.entry().normalizedEntryPrice();
        BigDecimal riskEntry = reference;
        BigDecimal observed = reference;
        boolean market = "MARKET".equalsIgnoreCase(intent.entryPolicy());
        if (market) {
            String rejection = validateMarketQuote(intent.intendedEntryPrice(), shortSide, quote, now);
            if (rejection != null) {
                return Estimate.rejected(rejection);
            }
            observed = shortSide ? quote.bestBid() : quote.bestAsk();
            riskEntry = shortSide ? reference.min(observed) : reference.max(observed);
        }
        BigDecimal stop = compiled.stopLoss().normalizedPrice();
        BigDecimal stopReference = market ? (shortSide ? quote.bestAsk() : quote.bestBid()) : reference;
        if (signedMove(stop, stopReference, shortSide).signum() >= 0
                || signedMove(compiled.takeProfitLegs().get(0).executableTargetPrice(), riskEntry, shortSide).signum() <= 0) {
            return Estimate.rejected("MARKET_ENTRY_OUTSIDE_EXIT_RANGE");
        }
        if (limits.riskRequest() != null) {
            var policy = riskService.check(limits.riskRequest().withExecutionPrices(riskEntry, stop)
                    .withSuggestedMargin(BigDecimal.ZERO));
            if (!policy.passed()) {
                return Estimate.rejected(policy.rejectCode() + ": " + policy.rejectReason());
            }
            riskBudget = riskBudget.min(policy.maxLossAmount());
            if (riskBudget.signum() <= 0) {
                return Estimate.rejected("EXECUTION_RISK_BUDGET_UNAVAILABLE");
            }
        }

        BigDecimal costRate = fees.roundTrip().max(properties.profitProtection().roundTripFeePct().movePointLeft(2))
                .add(properties.profitProtection().estimatedSlippagePct().movePointLeft(2))
                .add(properties.market().maxFundingAbs());
        BigDecimal finalTarget = compiled.takeProfitLegs().get(compiled.takeProfitLegs().size() - 1)
                .executableTargetPrice();
        BigDecimal stopMove = riskEntry.subtract(stop).abs();
        BigDecimal costPerUnit = riskEntry.max(observed).max(stop).max(finalTarget).multiply(costRate);
        BigDecimal lossPerUnit = stopMove.add(costPerUnit);
        // For sells use the higher notional estimate for margin even when the lower bid is worse for P/L.
        BigDecimal marginPrice = reference.max(observed);
        BigDecimal marginPerUnit = marginPrice.divide(BigDecimal.valueOf(limits.leverage()), PRECISION);
        BigDecimal openFeePerUnit = marginPrice.multiply(fees.maker().max(fees.taker()));
        BigDecimal basePerContract = rules.contractValueInQuoteCurrencyForAudit()
                ? rules.contractValue().divide(reference, PRECISION) : rules.contractValue();
        BigDecimal lotBase = basePerContract.multiply(rules.lotSize());
        BigDecimal maxMargin = limits.maxMargin();
        if (market) {
            BigDecimal depthMargin = quote.availableDepthUsdt().multiply(properties.market().maxDepthUsageRate())
                    .divide(BigDecimal.valueOf(limits.leverage()), PRECISION);
            maxMargin = maxMargin.min(depthMargin);
        }
        BigDecimal maxLots = riskBudget.divide(lossPerUnit.multiply(lotBase), 0, RoundingMode.DOWN)
                .min(maxMargin.divide(marginPerUnit.multiply(lotBase), 0, RoundingMode.DOWN))
                .min(limits.availableBalance().divide(marginPerUnit.add(openFeePerUnit).multiply(lotBase), 0, RoundingMode.DOWN));
        BigDecimal maxContracts = maxLots.multiply(rules.lotSize());
        if (maxContracts.compareTo(rules.minSize()) < 0) {
            return Estimate.rejected("NET_RISK_OR_MARGIN_BELOW_MINIMUM_SIZE");
        }
        if (compiled.entry().executableContractQuantity().compareTo(maxContracts) > 0) {
            compiled = compiler.compileAutomaticEntry(intent.withSizing(
                    baseForIntent(maxContracts, intent.intendedEntryPrice(), rules), riskBudget), rules);
            if (!compiled.accepted()) {
                return Estimate.rejected("CANONICAL_PLAN_REJECTED: " + String.join(",", compiled.decisions()));
            }
        }
        BigDecimal quantity = compiled.entry().executableContractQuantity();
        BigDecimal base = quantity.multiply(basePerContract);
        BigDecimal netLoss = lossPerUnit.multiply(base);
        BigDecimal weightedReward = BigDecimal.ZERO;
        for (CanonicalExecutablePlan.ExitLeg leg : compiled.takeProfitLegs()) {
            weightedReward = weightedReward.add(signedMove(leg.executableTargetPrice(), riskEntry, shortSide)
                    .multiply(leg.executableContractQuantity()));
        }
        BigDecimal netReward = weightedReward.multiply(basePerContract);
        if (!netTargets) {
            netReward = netReward.subtract(costPerUnit.multiply(base));
        }
        BigDecimal ratio = netReward.divide(netLoss, 8, RoundingMode.FLOOR);
        if (netLoss.compareTo(riskBudget) > 0 || quantity.compareTo(maxContracts) > 0) {
            return Estimate.rejected("NET_STOP_LOSS_EXCEEDS_RISK_BUDGET");
        }
        if (ratio.compareTo(limits.minimumRatio()) < 0) {
            return Estimate.rejected("NET_RISK_REWARD_AFTER_COSTS_TOO_LOW: "
                    + ratio.setScale(4, RoundingMode.DOWN).toPlainString() + " < " + limits.minimumRatio().toPlainString());
        }
        CanonicalExecutablePlan.Risk risk = new CanonicalExecutablePlan.Risk(riskBudget,
                compiled.risk().executableStopLossAmount(), riskEntry, netLoss, ratio, market ? quote.createdAt() : null);
        return new Estimate(compiled.withRisk(risk), base, marginPerUnit.multiply(base), null);
    }

    private String validateMarketQuote(BigDecimal reference, boolean shortSide, OrderBookLiquiditySnapshot quote, Instant now) {
        if (quote == null || !quote.tradable() || quote.createdAt() == null) {
            return "MARKET_EXECUTION_BOOK_UNAVAILABLE"
                    + (quote == null || quote.denyReason() == null || quote.denyReason().isBlank()
                            ? "" : ": " + quote.denyReason());
        }
        if (!isFreshMarketQuote(quote.createdAt(), now)) {
            return "MARKET_EXECUTION_BOOK_STALE";
        }
        BigDecimal price = shortSide ? quote.bestBid() : quote.bestAsk();
        if (price == null || price.signum() <= 0 || quote.bestBid() == null || quote.bestAsk() == null
                || quote.bestBid().signum() <= 0 || quote.bestAsk().compareTo(quote.bestBid()) <= 0) {
            return "MARKET_EXECUTION_PRICE_UNAVAILABLE";
        }
        BigDecimal deviation = price.subtract(reference).abs().multiply(BigDecimal.valueOf(100)).divide(reference, PRECISION);
        if (deviation.compareTo(properties.preConfirmRefresh().maxPriceDeviationPct()) > 0) {
            return "MARKET_PRICE_DEVIATION_TOO_WIDE: " + deviation.setScale(4, RoundingMode.HALF_UP) + "%";
        }
        return null;
    }

    public static boolean isFreshMarketQuote(Instant quoteTime, Instant now) {
        long age = Duration.between(quoteTime, now).toMillis();
        return age >= -2000 && age <= 5000;
    }

    private static BigDecimal signedMove(BigDecimal target, BigDecimal entry, boolean shortSide) {
        return shortSide ? entry.subtract(target) : target.subtract(entry);
    }

    private static BigDecimal baseForIntent(BigDecimal contracts, BigDecimal entry, OkxInstrumentRules rules) {
        BigDecimal amount = contracts.multiply(rules.contractValue());
        return rules.contractValueInQuoteCurrencyForAudit()
                ? amount.divide(entry, 32, RoundingMode.UP) : amount;
    }
}
