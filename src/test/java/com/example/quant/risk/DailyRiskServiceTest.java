package com.example.quant.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.quant.config.AgentProperties;
import com.example.quant.ledger.LedgerPnlService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DailyRiskServiceTest {

    @Test
    void idempotentClaimsSurviveRestartAndBusinessDayRollover() {
        Fixture fixture = new Fixture();
        fixture.properties.risk().setMaxDailyTrades(2);
        Instant dayOne = Instant.parse("2026-08-10T23:59:00Z");

        DailyRiskService firstProcess = fixture.service();
        assertThat(firstProcess.claimEntry("alice", "order-1", new BigDecimal("1000"), dayOne).allowed()).isTrue();
        assertThat(firstProcess.claimEntry("alice", "order-1", new BigDecimal("1000"), dayOne).allowed()).isTrue();
        DailyRiskService afterRestart = fixture.service();
        assertThat(afterRestart.claimEntry("alice", "order-2", new BigDecimal("1000"), dayOne).allowed()).isTrue();
        assertThat(afterRestart.claimEntry("alice", "order-3", new BigDecimal("1000"), dayOne).reason())
                .isEqualTo("MAX_DAILY_TRADES_REACHED");

        assertThat(afterRestart.claimEntry("alice", "order-3", new BigDecimal("1000"),
                Instant.parse("2026-08-11T16:01:00Z")).allowed()).isTrue();
    }

    @Test
    void blocksUnknownLedgerAndDailyLossAtConfiguredThreshold() {
        Fixture fixture = new Fixture();
        fixture.ledgerSummary = new LedgerPnlService.Summary(null, null, null, null, null,
                "LEDGER_SYNC_REQUIRED");
        assertThat(fixture.service().claimEntry("alice", "order-1", new BigDecimal("1000"),
                Instant.parse("2026-08-10T00:00:00Z")).reason()).isEqualTo("DAILY_LEDGER_SYNC_REQUIRED");

        fixture.ledgerSummary = summary("-300");
        assertThat(fixture.service().claimEntry("alice", "order-1", new BigDecimal("1000"),
                Instant.parse("2026-08-10T00:00:00Z")).reason()).isEqualTo("MAX_DAILY_LOSS_REACHED");
    }

    @Test
    void reconciledLossActivatesCooldownAndDefinitiveFailureReleasesCount() {
        Fixture fixture = new Fixture();
        fixture.properties.risk().setCooldownMinutesAfterLoss(30);
        Instant now = Instant.parse("2026-08-10T00:00:00Z");
        DailyRiskService service = fixture.service();

        assertThat(service.claimEntry("alice", "order-1", new BigDecimal("1000"), now).allowed()).isTrue();
        service.recordReconciledOutcome("alice", "order-1", new BigDecimal("-10"), now.plusSeconds(60));
        assertThat(service.claimEntry("alice", "order-2", new BigDecimal("1000"), now.plusSeconds(120)).reason())
                .isEqualTo("LOSS_COOLDOWN_ACTIVE");
        assertThat(service.claimEntry("alice", "order-2", new BigDecimal("1000"), now.plusSeconds(31 * 60)).allowed())
                .isTrue();
        service.releaseDefinitiveFailure("alice", "order-2", now.plusSeconds(32 * 60));
        assertThat(fixture.states.values()).singleElement().extracting(DailyRiskStateEntity::getEntryCount)
                .isEqualTo(1);
    }

    private static LedgerPnlService.Summary summary(String today) {
        BigDecimal value = new BigDecimal(today);
        return new LedgerPnlService.Summary(value, BigDecimal.ZERO, BigDecimal.ZERO, value, value,
                "EXCHANGE_RECENT_LEDGER");
    }

    private static final class Fixture {
        private final AgentProperties properties = new AgentProperties();
        private final Map<String, DailyRiskStateEntity> states = new HashMap<>();
        private final Map<String, DailyRiskEntryClaimEntity> claims = new HashMap<>();
        private LedgerPnlService.Summary ledgerSummary = summary("0");

        private DailyRiskService service() {
            DailyRiskStateRepository stateRepository = mock(DailyRiskStateRepository.class);
            when(stateRepository.findForUpdate(anyString(), any(LocalDate.class))).thenAnswer(invocation ->
                    Optional.ofNullable(states.get(key(invocation.getArgument(0), invocation.getArgument(1)))));
            when(stateRepository.findFirstByUserNameOrderByTradeDayDesc(anyString())).thenAnswer(invocation ->
                    states.values().stream()
                            .filter(state -> state.getUserName().equals(invocation.getArgument(0)))
                            .max(java.util.Comparator.comparing(DailyRiskStateEntity::getTradeDay)));
            when(stateRepository.save(any(DailyRiskStateEntity.class))).thenAnswer(invocation -> {
                DailyRiskStateEntity state = invocation.getArgument(0);
                states.put(key(state.getUserName(), state.getTradeDay()), state);
                return state;
            });
            DailyRiskEntryClaimRepository claimRepository = mock(DailyRiskEntryClaimRepository.class);
            when(claimRepository.findByUserNameAndTradeDayAndEntryKey(anyString(), any(LocalDate.class), anyString()))
                    .thenAnswer(invocation -> Optional.ofNullable(claims.get(claimKey(
                            invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2)))));
            when(claimRepository.findFirstByUserNameAndEntryKey(anyString(), anyString())).thenAnswer(invocation ->
                    claims.values().stream()
                            .filter(claim -> claim.getUserName().equals(invocation.getArgument(0))
                                    && claim.getEntryKey().equals(invocation.getArgument(1)))
                            .findFirst());
            when(claimRepository.save(any(DailyRiskEntryClaimEntity.class))).thenAnswer(invocation -> {
                DailyRiskEntryClaimEntity claim = invocation.getArgument(0);
                claims.put(claimKey(claim.getUserName(), claim.getTradeDay(), claim.getEntryKey()), claim);
                return claim;
            });
            doAnswer(invocation -> {
                DailyRiskEntryClaimEntity claim = invocation.getArgument(0);
                claims.remove(claimKey(claim.getUserName(), claim.getTradeDay(), claim.getEntryKey()));
                return null;
            }).when(claimRepository).delete(any(DailyRiskEntryClaimEntity.class));
            LedgerPnlService ledger = mock(LedgerPnlService.class);
            when(ledger.summary(anyString())).thenAnswer(ignored -> ledgerSummary);
            return new DailyRiskService(properties, stateRepository, claimRepository, ledger);
        }

        private static String key(String owner, LocalDate day) {
            return owner + "|" + day;
        }

        private static String claimKey(String owner, LocalDate day, String entryKey) {
            return key(owner, day) + "|" + entryKey;
        }
    }
}
