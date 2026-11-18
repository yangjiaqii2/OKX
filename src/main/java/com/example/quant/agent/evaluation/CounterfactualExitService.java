package com.example.quant.agent.evaluation;

import com.example.quant.account.OkxCredentialStore;
import com.example.quant.auth.AuthUserContext;
import com.example.quant.crypto.dto.ContractCandle;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CounterfactualExitService {
    public static final List<String> POLICIES = List.of(
            "BASELINE", "S1_V2", "TIME_60", "TIME_120", "TIME_240", "BE_075", "TRAIL_1R");
    private final CounterfactualExitRepository repository;

    public CounterfactualExitService(CounterfactualExitRepository repository) { this.repository = repository; }

    @Transactional
    public void evaluateAndPersist(Entry entry, List<ContractCandle> candles) {
        String owner = AuthUserContext.currentUsername().orElse(OkxCredentialStore.SYSTEM_USER);
        List<ContractCandle> path = candles == null ? List.of() : candles.stream()
                .filter(candle -> candle.timestamp() != null && !candle.timestamp().isBefore(entry.entryTime()))
                .toList();
        boolean pathComplete = !path.isEmpty()
                && Duration.between(entry.entryTime(), path.get(0).timestamp()).toMinutes() <= 10;
        for (String policy : POLICIES) {
            Outcome outcome = pathComplete ? evaluate(policy, entry, path)
                    : new Outcome("DATA_GAP", null, null, null, null, null, false);
            CounterfactualExitEntity row = repository
                    .findByUserNameAndPendingOrderIdAndPolicy(owner, entry.pendingOrderId(), policy)
                    .orElseGet(CounterfactualExitEntity::new);
            row.setUserName(owner); row.setPendingOrderId(entry.pendingOrderId()); row.setTradePlanId(entry.tradePlanId());
            row.setPolicy(policy); row.setInstId(entry.instId()); row.setDirection(entry.direction());
            row.setEntryTime(entry.entryTime()); row.setEntryPrice(entry.entryPrice()); row.setQuantity(entry.quantity());
            row.setRiskDistance(entry.riskDistance()); row.setExitTime(outcome.exitTime()); row.setExitPrice(outcome.exitPrice());
            row.setGrossPnl(outcome.gross()); row.setFee(null); row.setNetPnl(null); row.setPnlR(outcome.pnlR());
            row.setHoldMinutes(outcome.holdMinutes()); row.setStatus(outcome.status());
            row.setSignalVenue("PUBLIC_LIVE"); row.setExecutionVenue("PUBLIC_COUNTERFACTUAL");
            row.setDataQuality(!pathComplete ? "INSUFFICIENT_PUBLIC_CANDLE_WINDOW"
                    : outcome.ambiguous() ? "AMBIGUOUS_INTRABAR_AND_FEE_UNAVAILABLE" : "FEE_UNAVAILABLE");
            row.setUpdatedAt(Instant.now()); repository.save(row);
        }
    }

    private static Outcome evaluate(String policy, Entry e, List<ContractCandle> candles) {
        BigDecimal sign = "SHORT".equalsIgnoreCase(e.direction()) ? BigDecimal.valueOf(-1) : BigDecimal.ONE;
        BigDecimal stop = e.entryPrice().subtract(e.riskDistance().multiply(sign));
        BigDecimal target = e.takeProfitPrice() == null ?
                e.entryPrice().add(e.riskDistance().multiply(BigDecimal.valueOf(3)).multiply(sign))
                : e.takeProfitPrice();
        boolean armedBe = false; BigDecimal best = e.entryPrice();
        for (ContractCandle candle : candles) {
            long minutes = Math.max(0, Duration.between(e.entryTime(), candle.timestamp()).toMinutes());
            BigDecimal favorable = sign.signum() > 0 ? candle.high().subtract(e.entryPrice()) : e.entryPrice().subtract(candle.low());
            if ("BE_075".equals(policy) && favorable.compareTo(e.riskDistance().multiply(new BigDecimal("0.75"))) >= 0) armedBe = true;
            if ("TRAIL_1R".equals(policy) && favorable.compareTo(e.riskDistance()) >= 0) {
                best = sign.signum() > 0 ? best.max(candle.high()) : best.min(candle.low());
                stop = best.subtract(e.riskDistance().multiply(sign));
            }
            if (armedBe) stop = e.entryPrice();
            boolean stopHit = sign.signum() > 0 ? candle.low().compareTo(stop) <= 0 : candle.high().compareTo(stop) >= 0;
            boolean targetHit = sign.signum() > 0 ? candle.high().compareTo(target) >= 0 : candle.low().compareTo(target) <= 0;
            if (stopHit || targetHit) return outcome(e, candle.timestamp(), stopHit ? stop : target, sign, stopHit && targetHit);
            long limit = switch (policy) { case "TIME_60", "S1_V2" -> 60; case "TIME_120" -> 120; case "TIME_240" -> 240; default -> Long.MAX_VALUE; };
            if (minutes >= limit) return outcome(e, candle.timestamp(), candle.close(), sign, false);
        }
        return new Outcome("OPEN", null, null, null, null, null, false);
    }

    private static Outcome outcome(Entry e, Instant at, BigDecimal price, BigDecimal sign, boolean ambiguous) {
        BigDecimal gross = price.subtract(e.entryPrice()).multiply(sign).multiply(e.quantity());
        BigDecimal risk = e.riskDistance().multiply(e.quantity());
        BigDecimal pnlR = risk.signum() == 0 ? null : gross.divide(risk, 8, RoundingMode.HALF_UP);
        return new Outcome("EXITED", at, price, gross, pnlR,
                Duration.between(e.entryTime(), at).toMinutes(), ambiguous);
    }

    public record Entry(String pendingOrderId, String tradePlanId, String instId, String direction,
                        Instant entryTime, BigDecimal entryPrice, BigDecimal quantity, BigDecimal riskDistance,
                        BigDecimal takeProfitPrice) {}
    private record Outcome(String status, Instant exitTime, BigDecimal exitPrice, BigDecimal gross,
                           BigDecimal pnlR, Long holdMinutes, boolean ambiguous) {}
}
