package com.example.quant.agent.exit;

import com.example.quant.account.OkxCredentialStore;
import com.example.quant.account.dto.PositionSummary;
import com.example.quant.agent.event.TradeEventPayload;
import com.example.quant.agent.event.TradeEventService;
import com.example.quant.agent.event.TradeEventType;
import com.example.quant.auth.AuthUserContext;
import com.example.quant.config.AgentProperties;
import com.example.quant.okxtrade.OkxInstrumentRules;
import com.example.quant.okxtrade.OkxOrderGateway;
import com.example.quant.okxtrade.TradingOperationContext;
import com.example.quant.ledger.TradeFillEntity;
import com.example.quant.ledger.TradeFillRepository;
import com.example.quant.okxtrade.TradingOperationType;
import com.example.quant.order.PendingOrder;
import com.example.quant.tradeplan.CanonicalExecutablePlan;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Orchestrates durable profit protection for one managed position.
 *
 * <p>This class is intentionally the only place that talks to infrastructure.
 * All decisions come from {@link ProfitProtectionEngine}, which is pure, so the
 * policy itself stays replayable and unit-testable without Spring.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Keep {@code position_exit_state} in sync with the exchange.</li>
 *   <li>Fold each observed price into MFE/MAE.</li>
 *   <li>Ask the engine what the protective stop should be.</li>
 *   <li>Apply it with read-back confirmation, never blind retries.</li>
 * </ul>
 *
 * <p>It never closes a position.
 */
@Service
public class PositionExitService {
    private static final Logger log = LoggerFactory.getLogger(PositionExitService.class);

    private final PositionExitStateRepository repository;
    private final OkxOrderGateway okxOrderGateway;
    private final AgentProperties agentProperties;
    private TradeEventService tradeEventService;
    private TradeFillRepository tradeFillRepository;

    public PositionExitService(PositionExitStateRepository repository, OkxOrderGateway okxOrderGateway,
                               AgentProperties agentProperties) {
        this.repository = repository;
        this.okxOrderGateway = okxOrderGateway;
        this.agentProperties = agentProperties == null ? new AgentProperties() : agentProperties;
    }

    @Autowired(required = false)
    public void setTradeEventService(TradeEventService tradeEventService) {
        this.tradeEventService = tradeEventService;
    }

    @Autowired(required = false)
    public void setTradeFillRepository(TradeFillRepository tradeFillRepository) {
        this.tradeFillRepository = tradeFillRepository;
    }

    /**
     * Reconcile durable exit state against the exchange and move the protective
     * stop toward profit when the engine asks for it.
     *
     * @return what happened; never throws for recoverable conditions.
     */
    public ExitRunResult reconcileAndProtect(PendingOrder order, PositionSummary position,
                                             OkxInstrumentRules rules, ExchangeStopView exchangeStop,
                                             Instant now) {
        ProfitProtectionConfig config = agentProperties.profitProtection().toConfig();
        if (!config.enabled() || order == null || order.id() == null || position == null) {
            return ExitRunResult.skipped("PROFIT_PROTECTION_DISABLED_OR_NO_POSITION");
        }
        String owner = currentUsername();
        String pendingOrderId = order.id().toString();

        BigDecimal entryPrice = entryPrice(order, position);
        BigDecimal initialStop = initialStopLoss(order);
        if (entryPrice == null || entryPrice.signum() <= 0 || initialStop == null || initialStop.signum() <= 0) {
            return ExitRunResult.skipped("EXIT_FACTS_UNAVAILABLE");
        }
        BigDecimal risk = entryPrice.subtract(initialStop).abs();
        if (risk.signum() <= 0) {
            return ExitRunResult.skipped("EXIT_FACTS_INVALID_INITIAL_RISK");
        }
        BigDecimal price = position.effectivePrice();
        if (price == null || price.signum() <= 0) {
            return ExitRunResult.skipped("EXIT_FACTS_INVALID_PRICE");
        }

        PositionExcursion.TradeSide side = PositionExcursion.TradeSide.from(order.action());
        PositionExitStateEntity state = loadOrCreate(owner, pendingOrderId, order, entryPrice, initialStop, risk, now);

        // The exchange is authoritative for the stop that is actually in force.
        BigDecimal applied = exchangeStop != null && exchangeStop.usable()
                ? exchangeStop.triggerPrice()
                : state.getAppliedProtectiveStop();

        PositionExcursion excursion = excursionFrom(state)
                .observe(side, entryPrice, risk, price, now);

        int amendsInWindow = state.getStopAmendCount();
        Instant windowStart = state.getStopAmendWindowStart();
        if (windowStart == null || Duration.between(windowStart, now).toHours() >= 1) {
            windowStart = now;
            amendsInWindow = 0;
        }

        ExitContext context = new ExitContext(
                order.action(), entryPrice, initialStop, price, excursion, applied,
                parseState(state.getExitState()),
                state.getStopAppliedAt(), amendsInWindow,
                rules == null ? null : rules.tickSize(), now, config);

        ExitDecision decision = ProfitProtectionEngine.evaluate(context);

        applyExcursion(state, excursion, price, now);
        // A price observation is not an exchange confirmation.  Keep the
        // desired stage separately until the stop read-back proves it.
        state.setObservedStage(decision.toState() == null ? null : decision.toState().name());
        if (exchangeStop != null && exchangeStop.usable()) {
            state.setStopAlgoClOrdId(exchangeStop.clientOrderId());
            if (exchangeStop.algoId() != null) {
                state.setStopAlgoId(exchangeStop.algoId());
            }
            // Fold the confirmed exchange value back so local state never claims
            // protection it does not have.
            if (MonotonicStopGuard.improves(exchangeStop.triggerPrice(), state.getAppliedProtectiveStop(), side)) {
                state.setAppliedProtectiveStop(exchangeStop.triggerPrice());
            }
        }
        state.setStopAmendCount(amendsInWindow);
        state.setStopAmendWindowStart(windowStart);

        if (!decision.tightens() || exchangeStop == null || !exchangeStop.usable()) {
            if (exchangeStop != null && exchangeStop.usable()
                    && (decision.toState() == PositionExitState.INITIAL_PROTECTION
                    || "PROTECTIVE_STOP_ALREADY_AT_TARGET".equals(decision.reason()))) {
                confirmProtectionStage(state, decision.toState(), now);
            }
            save(state);
            return ExitRunResult.noChange(decision);
        }
        return amendProtectiveStop(order, state, decision, exchangeStop, side, position.posSide(), now);
    }

    /**
     * Records the exit outcome for a position that is gone. {@code realizedR}
     * must come from real fills plus fees/funding; pass {@code null} when it is
     * not available yet and the giveback fields stay empty rather than being
     * invented from a trigger price.
     */
    public void recordClosure(PendingOrder order, BigDecimal realizedR, Instant now, String exitReason) {
        if (order == null || order.id() == null) {
            return;
        }
        String owner = currentUsername();
        Optional<PositionExitStateEntity> existing =
                repository.findByUserNameAndPendingOrderId(owner, order.id().toString());
        if (existing.isEmpty()) {
            return;
        }
        PositionExitStateEntity state = existing.get();
        if (state.getClosedAt() != null) {
            return;
        }
        BigDecimal effectiveRealizedR = realizedR != null
                ? realizedR
                : realizedGrossRFromFills(order, state.getEntryPrice(), state.getInitialRisk());
        state.setMfeRFinal(state.getMfeR());
        state.setRealizedR(effectiveRealizedR);
        GivebackCalculator.Giveback giveback = GivebackCalculator.calculate(state.getMfeR(), effectiveRealizedR);
        state.setGivebackR(giveback.givebackR());
        state.setGivebackRatio(giveback.givebackRatio());
        state.setExitReason(exitReason);
        state.setClosedAt(now);
        state.setExitState(PositionExitState.CLOSED.name());
        state.setObservedStage(PositionExitState.CLOSED.name());
        state.setConfirmedProtectionStage(PositionExitState.CLOSED.name());
        state.setStopAmendState("CLOSED");
        state.setUpdatedAt(now);
        save(state);
        recordEvent(order, TradeEventType.PROFIT_GIVEBACK_RECORDED, state.getExitState(),
                PositionExitState.CLOSED.name(), "PROFIT_GIVEBACK_RECORDED",
                "mfeR=" + state.getMfeR() + " realizedR=" + effectiveRealizedR
                        + " givebackR=" + giveback.givebackR(),
                null, null, null);
    }

    /**
     * Best-effort gross realised R derived from real exit fills. Gross only:
     * fees and funding live in the bill ledger and are not mixed in here.
     * Returns {@code null} rather than a guess when fills are unavailable.
     */
    private BigDecimal realizedGrossRFromFills(PendingOrder order, BigDecimal entryPrice, BigDecimal risk) {
        if (tradeFillRepository == null || order == null || order.id() == null
                || entryPrice == null || entryPrice.signum() <= 0 || risk == null || risk.signum() <= 0) {
            return null;
        }
        java.util.List<TradeFillEntity> fills;
        try {
            fills = tradeFillRepository.findByPendingOrderId(order.id().toString());
        } catch (RuntimeException ex) {
            return null;
        }
        if (fills == null || fills.isEmpty()) {
            return null;
        }
        String entrySide = order.side();
        BigDecimal notional = BigDecimal.ZERO;
        BigDecimal size = BigDecimal.ZERO;
        for (TradeFillEntity fill : fills) {
            if (fill.getSide() == null || entrySide != null && fill.getSide().equalsIgnoreCase(entrySide)) {
                continue;
            }
            if (fill.getFillPrice() == null || fill.getFillSize() == null || fill.getFillSize().signum() <= 0) {
                continue;
            }
            notional = notional.add(fill.getFillPrice().multiply(fill.getFillSize()));
            size = size.add(fill.getFillSize());
        }
        if (size.signum() <= 0) {
            return null;
        }
        BigDecimal exitVwap = notional.divide(size, 8, RoundingMode.HALF_UP);
        PositionExcursion.TradeSide side = PositionExcursion.TradeSide.from(order.action());
        return side.signed(entryPrice, exitVwap).divide(risk, 8, RoundingMode.HALF_UP);
    }

    private ExitRunResult amendProtectiveStop(PendingOrder order, PositionExitStateEntity state,
                                              ExitDecision decision, ExchangeStopView exchangeStop,
                                              PositionExcursion.TradeSide side, String exchangePositionSide, Instant now) {
        BigDecimal target = decision.targetProtectiveStop();
        String algoClOrdId = exchangeStop.clientOrderId();

        // 1. Read the exchange back before writing. This is what makes a
        //    timed-out amend converge on the next pass instead of duplicating it.
        Optional<BigDecimal> confirmed = readBackStop(order.instId(), algoClOrdId);
        if (confirmed.isEmpty()) {
            state.setStopAmendState("READBACK_PENDING");
            state.setDesiredProtectiveStop(target);
            state.setPendingSince(now);
            save(state);
            return ExitRunResult.readbackUnavailable(decision);
        }
        if (confirmed.get().compareTo(target) == 0) {
            state.setAppliedProtectiveStop(target);
            confirmProtectionStage(state, decision.toState(), now);
            state.setDesiredProtectiveStop(null);
            state.setPendingSince(null);
            state.setPendingReqId(null);
            state.setStopAmendState("APPLIED");
            state.setStopAppliedAt(now);
            save(state);
            return ExitRunResult.alreadyApplied(decision);
        }

        String reqId = amendRequestId(algoClOrdId, target);
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("instId", order.instId());
        payload.put("algoClOrdId", algoClOrdId);
        payload.put("posSide", exchangePositionSide == null ? "net" : exchangePositionSide);
        payload.put("newSlTriggerPx", target.stripTrailingZeros().toPlainString());
        payload.put("reqId", reqId);
        state.setPendingReqId(reqId);
        state.setDesiredProtectiveStop(target);
        state.setPendingSince(now);
        state.setStopAmendState("SUBMITTED");
        save(state);

        try {
            okxOrderGateway.amendAlgoOrder(payload, TradingOperationContext.of(
                    TradingOperationType.AMEND_PROTECTION, currentUsername(),
                    order.id().toString(),
                    order.tradePlanId() == null ? null : order.tradePlanId().toString()).withDirection(order.posSide()));
        } catch (RuntimeException ex) {
            state.setStopAmendState("RECONCILIATION_REQUIRED");
            log.warn("profit protection amend failed instId={} pendingOrderId={} reason={}",
                    order.instId(), order.id(), ex.getMessage());
            save(state);
            recordEvent(order, TradeEventType.PROFIT_PROTECTION_FAILED, decision.fromState().name(),
                    decision.toState().name(), "PROFIT_PROTECTION_AMEND_UNCERTAIN", ex.getMessage(),
                    null, algoClOrdId, state.getStopAlgoId());
            return ExitRunResult.failed(decision);
        }

        Optional<BigDecimal> after = readBackStop(order.instId(), algoClOrdId);
        if (after.isPresent() && after.get().compareTo(target) == 0) {
            state.setAppliedProtectiveStop(target);
            confirmProtectionStage(state, decision.toState(), now);
            state.setDesiredProtectiveStop(null);
            state.setPendingSince(null);
            state.setPendingReqId(null);
            state.setStopAmendState("APPLIED");
            state.setStopAppliedAt(now);
            state.setStopAmendCount(state.getStopAmendCount() + 1);
            if (state.getStopAmendWindowStart() == null) {
                state.setStopAmendWindowStart(now);
            }
            save(state);
            log.info("exit_decision pendingOrderId={} instId={} side={} entry={} initialSL={} markPx={} "
                            + "mfeR={} oldStop={} newStop={} exitState={}->{} action={} reason={}",
                    order.id(), order.instId(), side, state.getEntryPrice(), state.getInitialStopLoss(),
                    state.getLastMarkPrice(), state.getMfeR(), confirmed.get(), target,
                    decision.fromState(), decision.toState(), decision.action(), decision.reason());
            TradeEventType event = decision.toState() == PositionExitState.BREAK_EVEN_ARMED
                    || decision.toState() == PositionExitState.PROFIT_LOCKED
                    || decision.toState() == PositionExitState.TRAILING
                    ? TradeEventType.PROFIT_PROTECTION_ARMED
                    : TradeEventType.PROTECTIVE_STOP_TIGHTENED;
            recordEvent(order, event, decision.fromState().name(), decision.toState().name(),
                    decision.reason(), "protective stop moved to " + target, null, algoClOrdId,
                    state.getStopAlgoId());
            return ExitRunResult.applied(decision);
        }
        // Sent but not confirmed: keep the durable intent and let the next pass
        // read back. Never re-send blindly.
        state.setStopAmendState("RECONCILIATION_REQUIRED");
        save(state);
        recordEvent(order, TradeEventType.PROFIT_PROTECTION_FAILED, decision.fromState().name(),
                decision.toState().name(), "PROFIT_PROTECTION_AMEND_UNCERTAIN",
                "amend sent but exchange did not confirm target=" + target, null, algoClOrdId,
                state.getStopAlgoId());
        return ExitRunResult.uncertain(decision);
    }

    private Optional<BigDecimal> readBackStop(String instId, String algoClOrdId) {
        if (okxOrderGateway == null || algoClOrdId == null || algoClOrdId.isBlank()) {
            return Optional.empty();
        }
        Map<String, String> query = new LinkedHashMap<>();
        query.put("algoClOrdId", algoClOrdId);
        JsonNode response;
        try {
            response = okxOrderGateway.queryAlgoOrder(query);
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
        if (response == null) {
            return Optional.empty();
        }
        JsonNode data = response.path("data");
        if (!data.isArray() || data.isEmpty()) {
            return Optional.empty();
        }
        JsonNode item = data.get(0);
        String trigger = item.path("slTriggerPx").asText("");
        if (trigger.isBlank()) {
            return Optional.empty();
        }
        try {
            BigDecimal value = new BigDecimal(trigger);
            return value.signum() > 0 ? Optional.of(value) : Optional.empty();
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }
    }

    private PositionExitStateEntity loadOrCreate(String owner, String pendingOrderId, PendingOrder order,
                                                 BigDecimal entryPrice, BigDecimal initialStop,
                                                 BigDecimal risk, Instant now) {
        Optional<PositionExitStateEntity> existing = repository.findByUserNameAndPendingOrderId(owner, pendingOrderId);
        if (existing.isPresent()) {
            PositionExitStateEntity state = existing.get();
            // V36 rows predate the split.  Their persisted exitState was the
            // last confirmed stage, so backfill both views conservatively; do
            // not infer a newer stage from the current price.
            if (state.getConfirmedProtectionStage() == null) {
                state.setConfirmedProtectionStage(state.getExitState());
            }
            if (state.getObservedStage() == null) {
                state.setObservedStage(state.getExitState());
            }
            if (state.getStopAmendState() == null) {
                state.setStopAmendState("NONE");
            }
            return state;
        }
        PositionExitStateEntity entity = new PositionExitStateEntity();
        entity.setUserName(owner);
        entity.setPendingOrderId(pendingOrderId);
        entity.setTradePlanId(order.tradePlanId() == null ? null : order.tradePlanId().toString());
        entity.setInstId(order.instId());
        entity.setPosSide(order.posSide());
        entity.setEntryPrice(entryPrice);
        entity.setInitialStopLoss(initialStop);
        entity.setInitialRisk(risk);
        entity.setAction(order.action() == null ? null : order.action().name());
        entity.setExitState(PositionExitState.NEW.name());
        entity.setObservedStage(PositionExitState.NEW.name());
        entity.setConfirmedProtectionStage(PositionExitState.NEW.name());
        entity.setStopAmendState("NONE");
        entity.setExitStateSince(now);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return entity;
    }

    private static PositionExcursion excursionFrom(PositionExitStateEntity state) {
        return new PositionExcursion(state.getHighestSinceEntry(), state.getLowestSinceEntry(),
                state.getMfePrice(), state.getMaePrice(), state.getMfePct(), state.getMaePct(),
                state.getMfeR(), state.getMaeR(), state.getMfeAt(), state.getMaeAt());
    }

    private static void applyExcursion(PositionExitStateEntity state, PositionExcursion excursion,
                                       BigDecimal price, Instant now) {
        state.setHighestSinceEntry(excursion.highestSinceEntry());
        state.setLowestSinceEntry(excursion.lowestSinceEntry());
        state.setMfePrice(excursion.mfePrice());
        state.setMaePrice(excursion.maePrice());
        state.setMfePct(excursion.mfePct());
        state.setMaePct(excursion.maePct());
        state.setMfeR(excursion.mfeR());
        state.setMaeR(excursion.maeR());
        state.setMfeAt(excursion.mfeAt());
        state.setMaeAt(excursion.maeAt());
        state.setLastMarkPrice(price);
        state.setLastPriceAt(now);
    }

    private static void applyState(PositionExitStateEntity state, ExitDecision decision, Instant now) {
        PositionExitState current = parseState(state.getExitState());
        PositionExitState target = decision.toState();
        if (target != null && target != current && current.canTransitionTo(target)) {
            state.setExitState(target.name());
            state.setExitStateSince(now);
        }
    }

    private static void confirmProtectionStage(PositionExitStateEntity state,
                                                PositionExitState target, Instant now) {
        if (target == null) {
            return;
        }
        PositionExitState current = parseState(state.getExitState());
        if (target == current || current.canTransitionTo(target)) {
            state.setExitState(target.name());
            state.setConfirmedProtectionStage(target.name());
            if (target != current) {
                state.setExitStateSince(now);
            }
        }
    }

    private static PositionExitState parseState(String value) {
        if (value == null || value.isBlank()) {
            return PositionExitState.NEW;
        }
        try {
            return PositionExitState.valueOf(value);
        } catch (IllegalArgumentException ex) {
            return PositionExitState.NEW;
        }
    }

    private void save(PositionExitStateEntity state) {
        state.setUpdatedAt(Instant.now());
        try {
            repository.save(state);
        } catch (RuntimeException ex) {
            // A version conflict means another pass updated this row; the next
            // pass recomputes from the newer state instead of forcing a write.
            log.warn("profit protection state save skipped pendingOrderId={} reason={}",
                    state.getPendingOrderId(), ex.getMessage());
        }
    }

    private void recordEvent(PendingOrder order, TradeEventType type, String oldStatus, String newStatus,
                             String reasonCode, String reasonMessage, String okxOrdId, String clOrdId,
                             String algoId) {
        if (tradeEventService == null || order == null) {
            return;
        }
        try {
            tradeEventService.record(new TradeEventPayload(
                    null, order.instId(), order.id().toString(), null, null, type,
                    oldStatus, newStatus, reasonCode, reasonMessage, okxOrdId, clOrdId, algoId));
        } catch (RuntimeException ex) {
            log.warn("profit protection event persistence failed: {}", ex.getMessage());
        }
    }

    private static BigDecimal entryPrice(PendingOrder order, PositionSummary position) {
        if (position.avgPrice() != null && position.avgPrice().signum() > 0) {
            return position.avgPrice();
        }
        return order.price();
    }

    private static BigDecimal initialStopLoss(PendingOrder order) {
        try {
            CanonicalExecutablePlan plan = order.canonicalExecutablePlan();
            if (plan != null && plan.accepted() && plan.stopLoss() != null) {
                return plan.stopLoss().intendedPrice();
            }
        } catch (RuntimeException ex) {
            // Unknown schema must never be reconstructed from legacy fields.
            return null;
        }
        return order.stopLossPrice();
    }

    /** Stable per (order, target) id so a repeated amend is traceable at OKX. */
    private static String amendRequestId(String algoClOrdId, BigDecimal target) {
        String seed = algoClOrdId + ":" + target.stripTrailingZeros().toPlainString();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(seed.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte item : bytes) {
                builder.append(Character.forDigit((item >> 4) & 0xf, 16));
                builder.append(Character.forDigit(item & 0xf, 16));
            }
            return "ps" + builder.substring(0, 20);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private static String currentUsername() {
        return AuthUserContext.currentUsername().orElse(OkxCredentialStore.SYSTEM_USER);
    }

    /** Outcome of one {@link #reconcileAndProtect} pass. */
    public record ExitRunResult(
            Outcome outcome,
            String reason,
            ExitDecision decision
    ) {
        public enum Outcome {
            SKIPPED,
            NO_CHANGE,
            ALREADY_APPLIED,
            APPLIED,
            UNCERTAIN,
            READBACK_UNAVAILABLE,
            FAILED
        }

        static ExitRunResult skipped(String reason) {
            return new ExitRunResult(Outcome.SKIPPED, reason, null);
        }

        static ExitRunResult noChange(ExitDecision decision) {
            return new ExitRunResult(Outcome.NO_CHANGE, decision.reason(), decision);
        }

        static ExitRunResult alreadyApplied(ExitDecision decision) {
            return new ExitRunResult(Outcome.ALREADY_APPLIED, decision.reason(), decision);
        }

        static ExitRunResult applied(ExitDecision decision) {
            return new ExitRunResult(Outcome.APPLIED, decision.reason(), decision);
        }

        static ExitRunResult uncertain(ExitDecision decision) {
            return new ExitRunResult(Outcome.UNCERTAIN, decision.reason(), decision);
        }

        static ExitRunResult readbackUnavailable(ExitDecision decision) {
            return new ExitRunResult(Outcome.READBACK_UNAVAILABLE, decision.reason(), decision);
        }

        static ExitRunResult failed(ExitDecision decision) {
            return new ExitRunResult(Outcome.FAILED, decision.reason(), decision);
        }

        public boolean changed() {
            return outcome == Outcome.APPLIED || outcome == Outcome.ALREADY_APPLIED;
        }
    }
}
