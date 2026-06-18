package com.example.quant.agent.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.quant.account.PositionSnapshotService;
import com.example.quant.account.dto.PositionSummary;
import com.example.quant.auth.AuthUserContext;
import com.example.quant.config.TradingProperties;
import com.example.quant.system.SystemControlService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

class AutoTradeProfitServiceTest {
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-06-18T04:00:00Z"),
            ZoneId.of("Asia/Shanghai")
    );

    @Test
    void summarizesAutoTradeBudgetAndUnrealizedProfitFromRecordedSymbols() {
        AutoTradeRecordRepository repository = mock(AutoTradeRecordRepository.class);
        PositionSnapshotService positionSnapshotService = new FixedPositionSnapshotService(List.of(
                position("PEPE-USDT-SWAP", "1.20", "22.5"),
                position("MANUAL-USDT-SWAP", "9.99", "30")
        ));
        SystemControlService systemControlService = new SystemControlService(tradingProperties());
        systemControlService.enableAutoTrade(BigDecimal.valueOf(50));
        when(repository.findAll()).thenReturn(List.of(
                record("PEPE-USDT-SWAP", "EXECUTED", "22.5", "2026-06-18T01:10:00Z"),
                record("DOGE-USDT-SWAP", "EXECUTED", "15", "2026-06-17T01:10:00Z")
        ));
        AutoTradeProfitService service = new AutoTradeProfitService(repository, positionSnapshotService, systemControlService, CLOCK);

        AutoTradeProfitSummary summary = service.summary();

        assertThat(summary.totalBudgetUsdt()).isEqualByComparingTo("50");
        assertThat(summary.submittedMarginUsdt()).isEqualByComparingTo("37.5");
        assertThat(summary.todaySubmittedMarginUsdt()).isEqualByComparingTo("22.5");
        assertThat(summary.unrealizedPnlUsdt()).isEqualByComparingTo("1.20");
        assertThat(summary.totalNetPnlUsdt()).isEqualByComparingTo("1.20");
        assertThat(summary.openPositionCount()).isEqualTo(1);
        assertThat(summary.budgetRoiPct()).isEqualByComparingTo("2.40000000");
        assertThat(summary.usedMarginRoiPct()).isEqualByComparingTo("5.33333333");
        assertThat(summary.dataQuality()).isEqualTo("ESTIMATED_UNREALIZED_ONLY");
    }

    @Test
    void summarizesOnlyCurrentUsersAutoTradeRecords() {
        AutoTradeRecordRepository repository = mock(AutoTradeRecordRepository.class);
        PositionSnapshotService positionSnapshotService = new FixedPositionSnapshotService(List.of(
                position("PEPE-USDT-SWAP", "1.20", "22.5"),
                position("DOGE-USDT-SWAP", "8.80", "15")
        ));
        SystemControlService systemControlService = new SystemControlService(tradingProperties());
        systemControlService.enableAutoTrade(BigDecimal.valueOf(50));
        when(repository.findAll()).thenReturn(List.of(
                record("alice", "PEPE-USDT-SWAP", "EXECUTED", "22.5", "2026-06-18T01:10:00Z"),
                record("bob", "DOGE-USDT-SWAP", "EXECUTED", "15", "2026-06-18T01:10:00Z")
        ));
        AutoTradeProfitService service = new AutoTradeProfitService(repository, positionSnapshotService, systemControlService, CLOCK);

        AutoTradeProfitSummary summary = AuthUserContext.callAs("alice", service::summary);

        assertThat(summary.submittedMarginUsdt()).isEqualByComparingTo("22.5");
        assertThat(summary.unrealizedPnlUsdt()).isEqualByComparingTo("1.20");
        assertThat(summary.openPositionCount()).isEqualTo(1);
    }

    private static AutoTradeRecordEntity record(String instId, String status, String margin, String createdAt) {
        return record("local-admin", instId, status, margin, createdAt);
    }

    private static AutoTradeRecordEntity record(String username, String instId, String status, String margin, String createdAt) {
        AutoTradeRecordEntity entity = new AutoTradeRecordEntity();
        entity.setUserName(username);
        entity.setInstId(instId);
        entity.setStatus(status);
        entity.setTriggerType("SCHEDULER");
        entity.setMarginAmount(new BigDecimal(margin));
        entity.setCreatedAt(Instant.parse(createdAt));
        return entity;
    }

    private static PositionSummary position(String instId, String unrealizedPnl, String margin) {
        return new PositionSummary(
                instId,
                "long",
                "1",
                BigDecimal.ONE,
                new BigDecimal(unrealizedPnl),
                new BigDecimal(margin),
                new BigDecimal(margin),
                "cross",
                BigDecimal.ONE,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                "OKX_REAL"
        );
    }

    private static TradingProperties tradingProperties() {
        return new TradingProperties("SEMI_AUTO", true, true, 120, false);
    }

    private static final class FixedPositionSnapshotService extends PositionSnapshotService {
        private final List<PositionSummary> positions;

        private FixedPositionSnapshotService(List<PositionSummary> positions) {
            super(null);
            this.positions = positions;
        }

        @Override
        public List<PositionSummary> positions() {
            return positions;
        }
    }
}
