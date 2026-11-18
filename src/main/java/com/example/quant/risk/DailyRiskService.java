package com.example.quant.risk;

import com.example.quant.config.AgentProperties;
import com.example.quant.ledger.LedgerPnlService;
import com.example.quant.common.TradingDayResolver;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DailyRiskService {
    private final AgentProperties properties;
    private final DailyRiskStateRepository states;
    private final DailyRiskEntryClaimRepository claims;
    private final LedgerPnlService pnl;
    private final TradingDayResolver tradingDayResolver;

    public DailyRiskService(AgentProperties properties, DailyRiskStateRepository states,
                            DailyRiskEntryClaimRepository claims, LedgerPnlService pnl) {
        this.properties = properties;
        this.states = states;
        this.claims = claims;
        this.pnl = pnl;
        this.tradingDayResolver = new TradingDayResolver();
    }

    @Transactional
    public Decision claimEntry(String owner, String entryKey, BigDecimal accountEquity, Instant now) {
        if (owner == null || owner.isBlank() || entryKey == null || entryKey.isBlank()) {
            return Decision.blocked("DAILY_RISK_IDENTITY_MISSING");
        }
        if (accountEquity == null || accountEquity.signum() <= 0) {
            return Decision.blocked("ACCOUNT_EQUITY_UNAVAILABLE");
        }
        Instant effectiveNow = now == null ? Instant.now() : now;
        LocalDate day = tradingDayResolver.tradingDate(effectiveNow);
        if (claims.findByUserNameAndTradeDayAndEntryKey(owner, day, entryKey).isPresent()) {
            return Decision.allowed("DAILY_RISK_ENTRY_ALREADY_CLAIMED");
        }
        LedgerPnlService.Summary summary = pnl == null ? null : pnl.summary(owner);
        if (summary == null || summary.todayNetRealizedPnl() == null
                || !"EXCHANGE_RECENT_LEDGER".equals(summary.dataQuality())) {
            return Decision.blocked("DAILY_LEDGER_SYNC_REQUIRED");
        }
        DailyRiskStateEntity state = states.findForUpdate(owner, day)
                .orElseGet(() -> newState(owner, day, accountEquity, effectiveNow, latestState(owner)));
        BigDecimal baseline = positive(state.getStartingEquity(), accountEquity);
        BigDecimal maxDailyLoss = baseline.multiply(percentRate(properties.risk().maxDailyLossPercent()));
        if (summary.todayNetRealizedPnl().signum() < 0
                && summary.todayNetRealizedPnl().abs().compareTo(maxDailyLoss) >= 0) {
            return Decision.blocked("MAX_DAILY_LOSS_REACHED");
        }
        if (state.getEntryCount() >= Math.max(0, properties.risk().maxDailyTrades())) {
            return Decision.blocked("MAX_DAILY_TRADES_REACHED");
        }
        if (state.getConsecutiveLosses() >= Math.max(0, properties.risk().maxConsecutiveLosses())) {
            return Decision.blocked("MAX_CONSECUTIVE_LOSSES_REACHED");
        }
        if (state.getLastLossAt() != null && Duration.between(state.getLastLossAt(), effectiveNow).toMinutes()
                < Math.max(0, properties.risk().cooldownMinutesAfterLoss())) {
            return Decision.blocked("LOSS_COOLDOWN_ACTIVE");
        }
        state.setStartingEquity(baseline);
        state.setEntryCount(state.getEntryCount() + 1);
        state.setUpdatedAt(effectiveNow);
        states.save(state);
        DailyRiskEntryClaimEntity claim = new DailyRiskEntryClaimEntity();
        claim.setUserName(owner);
        claim.setTradeDay(day);
        claim.setEntryKey(entryKey);
        claim.setClaimedAt(effectiveNow);
        claims.save(claim);
        return Decision.allowed("DAILY_RISK_ENTRY_CLAIMED");
    }

    @Transactional
    public void releaseDefinitiveFailure(String owner, String entryKey, Instant now) {
        claims.findFirstByUserNameAndEntryKey(owner, entryKey).ifPresent(claim -> {
            if (!claim.isOutcomeRecorded()) {
                states.findForUpdate(owner, claim.getTradeDay()).ifPresent(state -> {
                    state.setEntryCount(Math.max(0, state.getEntryCount() - 1));
                    state.setUpdatedAt(now == null ? Instant.now() : now);
                    states.save(state);
                });
                claims.delete(claim);
            }
        });
    }

    @Transactional
    public void recordReconciledOutcome(String owner, String entryKey, BigDecimal netPnl, Instant now) {
        if (netPnl == null) {
            return;
        }
        Instant effectiveNow = now == null ? Instant.now() : now;
        LocalDate outcomeDay = tradingDayResolver.tradingDate(effectiveNow);
        claims.findFirstByUserNameAndEntryKey(owner, entryKey).ifPresent(claim -> {
            if (claim.isOutcomeRecorded()) {
                return;
            }
            DailyRiskStateEntity state = states.findForUpdate(owner, outcomeDay)
                    .orElseGet(() -> newState(owner, outcomeDay, BigDecimal.ZERO, effectiveNow, latestState(owner)));
            if (netPnl.signum() < 0) {
                state.setConsecutiveLosses(state.getConsecutiveLosses() + 1);
                state.setLastLossAt(effectiveNow);
            } else if (netPnl.signum() > 0) {
                state.setConsecutiveLosses(0);
                state.setLastLossAt(null);
            }
            state.setUpdatedAt(effectiveNow);
            states.save(state);
            claim.setOutcomeRecorded(true);
            claim.setNetPnl(netPnl);
            claims.save(claim);
        });
    }

    private DailyRiskStateEntity latestState(String owner) {
        return states.findFirstByUserNameOrderByTradeDayDesc(owner).orElse(null);
    }

    private static DailyRiskStateEntity newState(String owner, LocalDate day, BigDecimal equity, Instant now,
                                                  DailyRiskStateEntity previous) {
        DailyRiskStateEntity state = new DailyRiskStateEntity();
        state.setUserName(owner);
        state.setTradeDay(day);
        state.setStartingEquity(equity);
        if (previous != null && previous.getTradeDay().isBefore(day)) {
            state.setConsecutiveLosses(previous.getConsecutiveLosses());
            state.setLastLossAt(previous.getLastLossAt());
        }
        state.setUpdatedAt(now);
        return state;
    }

    private static BigDecimal positive(BigDecimal preferred, BigDecimal fallback) {
        return preferred != null && preferred.signum() > 0 ? preferred : fallback;
    }

    private static BigDecimal percentRate(BigDecimal percent) {
        return percent == null ? BigDecimal.ZERO
                : percent.divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP);
    }

    public record Decision(boolean allowed, String reason) {
        static Decision allowed(String reason) { return new Decision(true, reason); }
        static Decision blocked(String reason) { return new Decision(false, reason); }
    }
}
