package com.example.quant.agent.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.quant.account.ClosePositionRecordEntity;
import com.example.quant.account.ClosePositionRecordRepository;
import com.example.quant.account.PositionSnapshotService;
import com.example.quant.account.dto.PositionSummary;
import com.example.quant.auth.AuthUserContext;
import com.example.quant.config.TradingProperties;
import com.example.quant.system.SystemControlService;
import com.example.quant.ledger.LedgerPnlService;
import com.example.quant.ledger.TradeFillEntity;
import com.example.quant.ledger.TradeFillRepository;
import com.example.quant.agent.execution.TradeOrderEntity;
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
        assertThat(summary.totalNetPnlUsdt()).isNull();
        assertThat(summary.openPositionCount()).isEqualTo(1);
        assertThat(summary.budgetRoiPct()).isNull();
        assertThat(summary.usedMarginRoiPct()).isNull();
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
                autoRecord("PEPE-USDT-SWAP", "EXECUTED", "auto-pending")
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

    @Test
    void closedRecordWithoutBillsDoesNotPretendUnknownRealizedPnlIsZero() {
        AutoTradeRecordRepository repository = mock(AutoTradeRecordRepository.class);
        ClosePositionRecordRepository closeRepository = mock(ClosePositionRecordRepository.class);
        SystemControlService systemControlService = new SystemControlService(tradingProperties());
        when(repository.findAll()).thenReturn(List.of(
                autoRecord("PEPE-USDT-SWAP", "ENTRY_SUBMITTED", "auto-pending")));
        ClosePositionRecordEntity closed = closeRecord("CLOSED", "0", "0", "0", "2026-06-18T01:10:00Z");
        closed.setRealizedPnl(null);
        closed.setFee(null);
        closed.setFundingFee(null);
        when(closeRepository.findByUserNameOrderByCreatedAtDesc(eq("local-admin"), any(Pageable.class)))
                .thenReturn(List.of(closed));
        AutoTradeProfitService service = new AutoTradeProfitService(
                repository,
                new FixedPositionSnapshotService(List.of()),
                systemControlService,
                closeRepository,
                CLOCK
        );

        AutoTradeProfitSummary summary = service.summary();

        assertThat(summary.realizedPnlUsdt()).isNull();
        assertThat(summary.todayRealizedPnlUsdt()).isNull();
        assertThat(summary.dataQuality()).isEqualTo("REALIZED_PNL_UNAVAILABLE");
        assertThat(summary.message()).contains("尚未接入").contains("fills/bill");
    }

    @Test
    void prefersExchangeLedgerAndExposesGrossFeesFundingAndNetSeparately() {
        AutoTradeRecordRepository repository = mock(AutoTradeRecordRepository.class);
        when(repository.findAll()).thenReturn(List.of(
                autoRecord("BTC-USDT-SWAP", "ENTRY_SUBMITTED", "p1")));
        LedgerPnlService ledger = mock(LedgerPnlService.class);
        when(ledger.summary("local-admin", java.util.Set.of("p1"))).thenReturn(new LedgerPnlService.Summary(
                new BigDecimal("10"), new BigDecimal("-0.20"), new BigDecimal("-1.20"),
                new BigDecimal("8.60"), BigDecimal.ZERO, "EXCHANGE_RECENT_LEDGER"));
        AutoTradeProfitService service = new AutoTradeProfitService(repository,
                new FixedPositionSnapshotService(List.of()), new SystemControlService(tradingProperties()), CLOCK);
        service.configureLedgerPnl(ledger);

        AutoTradeProfitSummary summary = service.summary();

        assertThat(summary.grossRealizedPnlUsdt()).isEqualByComparingTo("10");
        assertThat(summary.tradingFeesUsdt()).isEqualByComparingTo("-0.20");
        assertThat(summary.fundingUsdt()).isEqualByComparingTo("-1.20");
        assertThat(summary.fundingCompleteness()).isEqualTo("COMPLETE");
        assertThat(summary.realizedPnlUsdt()).isEqualByComparingTo("8.60");
        assertThat(summary.dataQuality()).isEqualTo("EXCHANGE_RECENT_LEDGER");
        verify(ledger).summary("local-admin", java.util.Set.of("p1"));
    }

    @Test
    void exposesFundingAsUnknownWithoutDiscardingKnownFillPnlAndFees() {
        AutoTradeRecordRepository repository = mock(AutoTradeRecordRepository.class);
        when(repository.findAll()).thenReturn(List.of(
                autoRecord("BTC-USDT-SWAP", "ENTRY_SUBMITTED", "p1")));
        LedgerPnlService ledger = mock(LedgerPnlService.class);
        when(ledger.summary("local-admin", java.util.Set.of("p1"))).thenReturn(new LedgerPnlService.Summary(
                new BigDecimal("10"), new BigDecimal("-0.20"), null,
                null, null, "AUTO_SCOPED_LEDGER_FUNDING_UNATTRIBUTED"));
        AutoTradeProfitService service = new AutoTradeProfitService(repository,
                new FixedPositionSnapshotService(List.of()), new SystemControlService(tradingProperties()), CLOCK);
        service.configureLedgerPnl(ledger);

        AutoTradeProfitSummary summary = service.summary();

        assertThat(summary.grossRealizedPnlUsdt()).isEqualByComparingTo("10");
        assertThat(summary.tradingFeesUsdt()).isEqualByComparingTo("-0.20");
        assertThat(summary.fundingUsdt()).isNull();
        assertThat(summary.fundingCompleteness()).isEqualTo("UNKNOWN");
        assertThat(summary.realizedPnlUsdt()).isNull();
        assertThat(summary.totalNetPnlUsdt()).isNull();
        assertThat(summary.dataQuality()).isEqualTo("AUTO_SCOPED_LEDGER_FUNDING_UNATTRIBUTED");
        assertThat(summary.message()).contains("无法唯一归属 lifecycle").contains("N/A");
    }

    @Test
    void positionSnapshotFailureDoesNotBecomeZeroUnrealizedPnl() {
        AutoTradeRecordRepository repository = mock(AutoTradeRecordRepository.class);
        when(repository.findAll()).thenReturn(List.of());
        PositionSnapshotService positions = new PositionSnapshotService(null) {
            @Override public List<PositionSummary> positions() {
                throw new IllegalStateException("timeout");
            }
        };
        AutoTradeProfitService service = new AutoTradeProfitService(
                repository, positions, new SystemControlService(tradingProperties()), CLOCK);

        AutoTradeProfitSummary summary = service.summary();

        assertThat(summary.unrealizedPnlUsdt()).isNull();
        assertThat(summary.totalNetPnlUsdt()).isNull();
        assertThat(summary.openPositionCount()).isEqualTo(-1);
        assertThat(summary.dataQuality()).isEqualTo("POSITION_SNAPSHOT_UNAVAILABLE");
    }

    @Test
    void countsEntryFillsByAutoLifecycleAndExcludesQaManualRecords() {
        AutoTradeRecordRepository records = mock(AutoTradeRecordRepository.class);
        TradeFillRepository fills = mock(TradeFillRepository.class);
        TradeOrderRepository orders = mock(TradeOrderRepository.class);
        when(records.findAll()).thenReturn(List.of(
                autoRecord("XAU-USDT-SWAP", "ENTRY_SUBMITTED", "p1"),
                autoRecord("BTC-USDT-SWAP", "ENTRY_SUBMITTED", "p2"),
                qaRecord("ETH-USDT-SWAP", "pqa")
        ));
        when(fills.findByUserName("local-admin")).thenReturn(List.of(
                fill("p1", "entry-1", "t1", "1"), fill("p1", "entry-1", "t2", "1"),
                fill("p2", "entry-2", "t3", "1")
        ));
        when(orders.findByUserNameAndPendingOrderIdOrderByCreatedAtAsc("local-admin", "p1"))
                .thenReturn(List.of(order("entry-1", false, "2")));
        when(orders.findByUserNameAndPendingOrderIdOrderByCreatedAtAsc("local-admin", "p2"))
                .thenReturn(List.of(order("entry-2", false, "1")));
        AutoTradeProfitService service = new AutoTradeProfitService(records,
                new FixedPositionSnapshotService(List.of()), new SystemControlService(tradingProperties()),
                null, CLOCK);
        service.configureTradeFacts(fills, orders, null);

        AutoTradeProfitSummary summary = service.summary();

        assertThat(summary.submittedTradeCount()).isEqualTo(2);
        assertThat(summary.entryFilledTradeCount()).isEqualTo(2);
        assertThat(summary.executedOrderCount()).isEqualTo(2);
    }

    @Test
    void threeAutoLifecyclesAndEthQaProduceThreeClosedAutoTrades() {
        AutoTradeRecordRepository records = mock(AutoTradeRecordRepository.class);
        ClosePositionRecordRepository closes = mock(ClosePositionRecordRepository.class);
        TradeFillRepository fills = mock(TradeFillRepository.class);
        TradeOrderRepository orders = mock(TradeOrderRepository.class);
        when(records.findAll()).thenReturn(List.of(
                autoRecord("XAU-USDT-SWAP", "ENTRY_SUBMITTED", "xau-1"),
                autoRecord("XAU-USDT-SWAP", "ENTRY_SUBMITTED", "xau-2"),
                autoRecord("BTC-USDT-SWAP", "ENTRY_SUBMITTED", "btc"),
                qaRecord("ETH-USDT-SWAP", "eth-qa")));
        when(closes.findByUserNameOrderByCreatedAtDesc(eq("local-admin"), any(Pageable.class)))
                .thenReturn(List.of(closeFor("xau-1", "-10"), closeFor("xau-2", "-23.2472"),
                        closeFor("btc", "-2.691673"), closeFor("eth-qa", "999")));
        when(fills.findByUserName("local-admin")).thenReturn(List.of(
                fill("xau-1", "entry-1", "e1", "717"), fill("xau-2", "entry-2", "e2", "717"),
                fill("btc", "entry-btc", "e3", "1")));
        when(orders.findByUserNameAndPendingOrderIdOrderByCreatedAtAsc("local-admin", "xau-1"))
                .thenReturn(List.of(order("entry-1", false, "717")));
        when(orders.findByUserNameAndPendingOrderIdOrderByCreatedAtAsc("local-admin", "xau-2"))
                .thenReturn(List.of(order("entry-2", false, "717")));
        when(orders.findByUserNameAndPendingOrderIdOrderByCreatedAtAsc("local-admin", "btc"))
                .thenReturn(List.of(order("entry-btc", false, "1")));
        AutoTradeProfitService service = new AutoTradeProfitService(records,
                new FixedPositionSnapshotService(List.of()), new SystemControlService(tradingProperties()),
                closes, CLOCK);
        service.configureTradeFacts(fills, orders, null);

        AutoTradeProfitSummary summary = service.summary();

        assertThat(summary.submittedTradeCount()).isEqualTo(3);
        assertThat(summary.entryFilledTradeCount()).isEqualTo(3);
        assertThat(summary.closedTradeCount()).isEqualTo(3);
        assertThat(summary.pnlCompleteTradeCount()).isEqualTo(3);
        assertThat(summary.realizedPnlUsdt()).isEqualByComparingTo("-35.938873");
    }

    private static AutoTradeRecordEntity autoRecord(String instId, String status, String pendingId) {
        AutoTradeRecordEntity entity = record(instId, status, "10", "2026-06-18T01:10:00Z");
        entity.setPendingOrderId(pendingId);
        return entity;
    }

    private static AutoTradeRecordEntity qaRecord(String instId, String pendingId) {
        AutoTradeRecordEntity entity = autoRecord(instId, "ENTRY_SUBMITTED", pendingId);
        entity.setTriggerType("QA");
        entity.setUserName("local-admin");
        return entity;
    }

    private static TradeFillEntity fill(String pendingId, String ordId, String tradeId, String size) {
        TradeFillEntity entity = new TradeFillEntity();
        entity.setUserName("local-admin"); entity.setPendingOrderId(pendingId);
        entity.setInstId("XAU-USDT-SWAP"); entity.setOrdId(ordId); entity.setTradeId(tradeId);
        entity.setFillSize(new BigDecimal(size)); entity.setFillPnl(BigDecimal.ZERO);
        entity.setFee(BigDecimal.ZERO); entity.setFillPrice(BigDecimal.ONE);
        entity.setFillTime(Instant.parse("2026-06-18T01:11:00Z"));
        return entity;
    }

    private static TradeOrderEntity order(String ordId, boolean reduceOnly, String filled) {
        TradeOrderEntity entity = new TradeOrderEntity();
        entity.setUserName("local-admin"); entity.setPendingOrderId(ordId.startsWith("entry-1") ? "p1" : "p2");
        entity.setOkxOrdId(ordId); entity.setReduceOnly(reduceOnly); entity.setFilledSize(new BigDecimal(filled));
        return entity;
    }

    private static ClosePositionRecordEntity closeFor(String pendingId, String pnl) {
        ClosePositionRecordEntity entity = closeRecord("CLOSED", pnl, "0", "0", "2026-06-18T01:10:00Z");
        entity.setPendingOrderId(pendingId);
        entity.setPnlCompleteness("COMPLETE");
        return entity;
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
        entity.setPendingOrderId("auto-pending");
        if ("CLOSED".equalsIgnoreCase(status)) entity.setPnlCompleteness("COMPLETE");
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
