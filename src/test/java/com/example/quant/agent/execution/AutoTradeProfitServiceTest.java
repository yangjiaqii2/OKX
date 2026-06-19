package com.example.quant.agent.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.quant.account.ClosePositionRecordEntity;
import com.example.quant.account.ClosePositionRecordRepository;
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
import org.springframework.data.domain.Pageable;

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

    @Test
    void summarizesRealizedNetProfitFromClosedCloseRecords() {
        AutoTradeRecordRepository repository = mock(AutoTradeRecordRepository.class);
        ClosePositionRecordRepository closeRepository = mock(ClosePositionRecordRepository.class);
        PositionSnapshotService positionSnapshotService = new FixedPositionSnapshotService(List.of());
        SystemControlService systemControlService = new SystemControlService(tradingProperties());
        systemControlService.enableAutoTrade(BigDecimal.valueOf(50));
        when(repository.findAll()).thenReturn(List.of(
                record("PEPE-USDT-SWAP", "EXECUTED", "22.5", "2026-06-18T01:10:00Z")
        ));
        when(closeRepository.findByUserNameOrderByCreatedAtDesc(eq("local-admin"), any(Pageable.class)))
                .thenReturn(List.of(
                        closeRecord("CLOSED", "2.00", "-0.10", "0.05", "2026-06-18T01:10:00Z"),
                        closeRecord("CLOSE_SUBMITTED", "8.00", "-0.20", "0", "2026-06-18T01:20:00Z"),
                        closeRecord("CLOSED", "1.00", "-0.02", "0", "2026-06-17T01:10:00Z")
                ));
        AutoTradeProfitService service = new AutoTradeProfitService(
                repository,
                positionSnapshotService,
                systemControlService,
                closeRepository,
                CLOCK
        );

        AutoTradeProfitSummary summary = service.summary();

        assertThat(summary.realizedPnlUsdt()).isEqualByComparingTo("2.93");
        assertThat(summary.todayRealizedPnlUsdt()).isEqualByComparingTo("1.95");
        assertThat(summary.totalNetPnlUsdt()).isEqualByComparingTo("2.93");
        assertThat(summary.dataQuality()).isEqualTo("CLOSE_RECORD_ESTIMATED");
        assertThat(summary.message()).contains("估算收益").contains("OKX fills/bill");
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

    private static ClosePositionRecordEntity closeRecord(String status, String realizedPnl, String fee,
                                                         String fundingFee, String createdAt) {
        ClosePositionRecordEntity entity = new ClosePositionRecordEntity();
        entity.setUserName("local-admin");
        entity.setInstId("PEPE-USDT-SWAP");
        entity.setPosSide("long");
        entity.setMarginMode("cross");
        entity.setStatus(status);
        entity.setSource("MANUAL");
        entity.setRealizedPnl(new BigDecimal(realizedPnl));
        entity.setFee(new BigDecimal(fee));
        entity.setFundingFee(new BigDecimal(fundingFee));
        entity.setCreatedAt(Instant.parse(createdAt));
        entity.setUpdatedAt(Instant.parse(createdAt));
        return entity;
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
