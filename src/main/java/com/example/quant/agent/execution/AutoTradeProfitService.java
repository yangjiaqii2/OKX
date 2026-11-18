package com.example.quant.agent.execution;

import com.example.quant.account.OkxCredentialStore;
import com.example.quant.account.ClosePositionRecordEntity;
import com.example.quant.account.ClosePositionRecordRepository;
import com.example.quant.account.PositionSnapshotService;
import com.example.quant.account.dto.PositionSummary;
import com.example.quant.auth.AuthUserContext;
import com.example.quant.system.SystemControlService;
import com.example.quant.ledger.LedgerPnlService;
import com.example.quant.ledger.TradeFillEntity;
import com.example.quant.ledger.TradeFillRepository;
import com.example.quant.common.TradingDayResolver;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AutoTradeProfitService {
    private static final ZoneId DISPLAY_ZONE = TradingDayResolver.BUSINESS_ZONE;
    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);

    private final AutoTradeRecordRepository recordRepository;
    private final ClosePositionRecordRepository closePositionRecordRepository;
    private final PositionSnapshotService positionSnapshotService;
    private final SystemControlService systemControlService;
    private final Clock clock;
    private final TradingDayResolver tradingDayResolver = new TradingDayResolver();
    private LedgerPnlService ledgerPnl;
    private TradeFillRepository tradeFillRepository;
    private TradeOrderRepository tradeOrderRepository;

    public AutoTradeProfitService(
            AutoTradeRecordRepository recordRepository,
            PositionSnapshotService positionSnapshotService,
            SystemControlService systemControlService
    ) {
        this(recordRepository, positionSnapshotService, systemControlService, null, Clock.system(DISPLAY_ZONE));
    }

    @Autowired
    public AutoTradeProfitService(
            AutoTradeRecordRepository recordRepository,
            PositionSnapshotService positionSnapshotService,
            SystemControlService systemControlService,
            ClosePositionRecordRepository closePositionRecordRepository
    ) {
        this(recordRepository, positionSnapshotService, systemControlService,
                closePositionRecordRepository, Clock.system(DISPLAY_ZONE));
    }

    AutoTradeProfitService(
            AutoTradeRecordRepository recordRepository,
            PositionSnapshotService positionSnapshotService,
            SystemControlService systemControlService,
            Clock clock
    ) {
        this(recordRepository, positionSnapshotService, systemControlService, null, clock);
    }

    AutoTradeProfitService(
            AutoTradeRecordRepository recordRepository,
            PositionSnapshotService positionSnapshotService,
            SystemControlService systemControlService,
            ClosePositionRecordRepository closePositionRecordRepository,
            Clock clock
    ) {
        this.recordRepository = recordRepository;
        this.closePositionRecordRepository = closePositionRecordRepository;
        this.positionSnapshotService = positionSnapshotService;
        this.systemControlService = systemControlService;
        this.clock = clock;
    }

    @Autowired(required = false)
    void configureLedgerPnl(LedgerPnlService ledgerPnl) {
        this.ledgerPnl = ledgerPnl;
    }

    @Autowired(required = false)
    void configureTradeFacts(TradeFillRepository tradeFillRepository, TradeOrderRepository tradeOrderRepository,
                             LedgerPnlService ignored) {
        this.tradeFillRepository = tradeFillRepository;
        this.tradeOrderRepository = tradeOrderRepository;
    }

    @Transactional(readOnly = true)
    public AutoTradeProfitSummary summary() {
        Instant now = clock.instant();
        String username = currentUsername();
        LocalDate today = tradingDayResolver.tradingDate(now);
        List<AutoTradeRecordEntity> records = recordRepository.findAll().stream()
                .filter(record -> "SCHEDULER".equalsIgnoreCase(nullToEmpty(record.getTriggerType())))
                .filter(record -> username.equals(nullToSystemUser(record.getUserName())))
                .toList();
        List<AutoTradeRecordEntity> submittedRecords = records.stream()
                .filter(record -> hasText(record.getPendingOrderId()))
                .filter(record -> !Set.of("FAILED", "REJECTED", "CANCELLED", "ENTRY_TIMEOUT_CANCELLED")
                        .contains(nullToEmpty(record.getStatus()).toUpperCase()))
                .toList();
        Set<String> entryFilledLifecycles = entryFilledLifecycles(username, submittedRecords);
        int executedCount = entryFilledLifecycles.size();
        Set<String> autoPendingIds = records.stream().map(AutoTradeRecordEntity::getPendingOrderId)
                .filter(AutoTradeProfitService::hasText).collect(java.util.stream.Collectors.toSet());
        CloseProfit closeProfit = realizedCloseProfit(username, today, autoPendingIds);
        Set<String> autoSymbols = new HashSet<>();
        BigDecimal submittedMargin = BigDecimal.ZERO;
        BigDecimal todaySubmittedMargin = BigDecimal.ZERO;
        for (AutoTradeRecordEntity record : records) {
            if (hasText(record.getInstId())) {
                autoSymbols.add(record.getInstId().trim().toUpperCase());
            }
            BigDecimal margin = zeroIfNull(record.getMarginAmount());
            submittedMargin = submittedMargin.add(margin);
            if (record.getCreatedAt() != null
                    && tradingDayResolver.tradingDate(record.getCreatedAt()).equals(today)) {
                todaySubmittedMargin = todaySubmittedMargin.add(margin);
            }
        }

        List<PositionSummary> positionSnapshot = safePositions();
        boolean positionsAvailable = positionSnapshot != null;
        BigDecimal unrealizedPnl = positionsAvailable ? BigDecimal.ZERO : null;
        BigDecimal usedMargin = positionsAvailable ? BigDecimal.ZERO : null;
        int openPositionCount = positionsAvailable ? 0 : -1;
        if (positionsAvailable) {
            for (PositionSummary position : positionSnapshot) {
                if (!autoSymbols.contains(nullToEmpty(position.instId()).toUpperCase())) {
                    continue;
                }
                if (decimal(position.size()).signum() == 0) {
                    continue;
                }
                openPositionCount++;
                unrealizedPnl = unrealizedPnl.add(zeroIfNull(position.unrealizedPnl()));
                usedMargin = usedMargin.add(effectiveMargin(position));
            }
            if (tradeOrderRepository != null && tradeFillRepository != null) {
                Set<String> closedIds = closedAutoPendingIds(username, autoPendingIds);
                openPositionCount = (int) submittedRecords.stream()
                        .filter(record -> entryFilledLifecycles.contains(record.getPendingOrderId()))
                        .filter(record -> !closedIds.contains(record.getPendingOrderId()))
                        .filter(record -> positionSnapshot.stream().anyMatch(position ->
                                hasText(position.instId()) && position.instId().equalsIgnoreCase(record.getInstId())
                                        && decimal(position.size()).signum() != 0))
                        .count();
            }
        }

        BigDecimal totalBudget = zeroIfNull(systemControlService.status().autoTradeMarginUsdt());
        LedgerPnlService.Summary ledger = ledgerPnl == null ? null : ledgerPnl.summary(username, autoPendingIds);
        boolean ledgerAvailable = ledger != null && ledger.netRealizedPnl() != null;
        boolean fundingUnattributed = ledger != null
                && "AUTO_SCOPED_LEDGER_FUNDING_UNATTRIBUTED".equals(ledger.dataQuality());
        boolean realizedAvailable = ledgerAvailable
                || (!fundingUnattributed && closeProfit.reconciledCount() > 0);
        BigDecimal realizedPnl = ledgerAvailable ? ledger.netRealizedPnl()
                : !fundingUnattributed && closeProfit.reconciledCount() > 0 ? closeProfit.realizedNetPnl() : null;
        BigDecimal todayRealizedPnl = ledgerAvailable ? ledger.todayNetRealizedPnl()
                : !fundingUnattributed && closeProfit.reconciledCount() > 0
                ? closeProfit.todayRealizedNetPnl() : null;
        BigDecimal totalNetPnl = realizedAvailable && positionsAvailable ? realizedPnl.add(unrealizedPnl) : null;
        String dataQuality = !positionsAvailable ? "POSITION_SNAPSHOT_UNAVAILABLE"
                : fundingUnattributed ? ledger.dataQuality()
                : ledgerAvailable ? ledger.dataQuality()
                : !realizedAvailable && closeProfit.closedCount() > 0
                ? "REALIZED_PNL_UNAVAILABLE"
                : closeProfit.reconciledCount() > 0 && closeProfit.reconciledCount() < closeProfit.closedCount()
                ? "PARTIAL_CLOSE_RECORD_ESTIMATED"
                : realizedAvailable ? "CLOSE_RECORD_ESTIMATED" : "ESTIMATED_UNREALIZED_ONLY";
        String message = !positionsAvailable
                ? "OKX持仓快照不可用：未实现收益、持仓数和总净收益均保持未知，不按0处理。"
                : fundingUnattributed
                ? "本系统可追溯 lifecycle 的毛收益与交易费来自OKX fills；资金费无法唯一归属 lifecycle，funding completeness=UNKNOWN，funding/net 为 N/A，不按0处理。"
                : ledgerAvailable
                ? "本系统可追溯订单的近期账本：毛收益与交易费来自OKX fills，资金费来自对应已管理合约的OKX bills；仅覆盖最近同步窗口，不代表完整账户历史。"
                : !realizedAvailable && closeProfit.closedCount() > 0
                ? "已实现收益不可用：平仓已确认，但尚未接入OKX fills/bill流水，不能把未知收益、手续费和资金费当作0；未实现收益来自OKX当前持仓。"
                : realizedAvailable
                ? "估算收益：已实现部分来自 close_position_record 的 realizedPnl + fee + fundingFee，未实现部分来自OKX当前持仓；完整真实净收益需接入OKX fills/bill流水。"
                : "估算收益：当前未找到已关闭平仓记录，未实现收益来自OKX当前持仓；完整真实净收益需接入成交均价、realizedPnl、fee、fundingFee和slippage。";
        return new AutoTradeProfitSummary(
                totalBudget,
                submittedMargin,
                todaySubmittedMargin,
                realizedPnl,
                todayRealizedPnl,
                unrealizedPnl,
                totalNetPnl,
                ratioPct(totalNetPnl, totalBudget),
                ratioPct(totalNetPnl, usedMargin),
                openPositionCount,
                executedCount,
                now,
                dataQuality,
                message,
                ledger == null ? null : ledger.grossRealizedPnl(),
                ledger == null ? null : ledger.tradingFees(),
                ledger == null ? null : ledger.funding(),
                ledgerAvailable && ledger.funding() != null ? "COMPLETE"
                        : fundingUnattributed ? "UNKNOWN" : "N/A",
                submittedRecords.size(),
                entryFilledLifecycles.size(),
                closeProfit.closedCount(),
                closeProfit.reconciledCount(),
                closeProfit.closedCount() - closeProfit.reconciledCount()
        );
    }

    private Set<String> entryFilledLifecycles(String username, List<AutoTradeRecordEntity> records) {
        Set<String> result = new HashSet<>();
        if (tradeFillRepository == null || tradeOrderRepository == null) return result;
        List<TradeFillEntity> fills = tradeFillRepository.findByUserName(username);
        for (AutoTradeRecordEntity record : records) {
            String pendingId = record.getPendingOrderId();
            if (fills.stream().filter(fill -> pendingId.equals(fill.getPendingOrderId()))
                    .anyMatch(fill -> tradeOrderRepository
                            .findByUserNameAndPendingOrderIdOrderByCreatedAtAsc(username, pendingId).stream()
                            .anyMatch(order -> !order.isReduceOnly()
                                    && order.getOkxOrdId() != null
                                    && order.getOkxOrdId().equals(fill.getOrdId())))) {
                result.add(pendingId);
            }
        }
        return result;
    }

    private Set<String> closedAutoPendingIds(String username, Set<String> autoPendingIds) {
        if (closePositionRecordRepository == null) return Set.of();
        Set<String> result = new HashSet<>();
        closePositionRecordRepository.findByUserNameOrderByCreatedAtDesc(username, Pageable.unpaged()).stream()
                .filter(record -> "CLOSED".equalsIgnoreCase(nullToEmpty(record.getStatus())))
                .map(ClosePositionRecordEntity::getPendingOrderId)
                .filter(autoPendingIds::contains)
                .forEach(result::add);
        return result;
    }

    private CloseProfit realizedCloseProfit(String username, LocalDate today, Set<String> autoPendingIds) {
        if (closePositionRecordRepository == null) {
            return new CloseProfit(BigDecimal.ZERO, BigDecimal.ZERO, 0, 0);
        }
        BigDecimal realized = BigDecimal.ZERO;
        BigDecimal todayRealized = BigDecimal.ZERO;
        int closedCount = 0;
        int reconciledCount = 0;
        for (ClosePositionRecordEntity record : closePositionRecordRepository
                .findByUserNameOrderByCreatedAtDesc(username, Pageable.unpaged())) {
            if (!"CLOSED".equalsIgnoreCase(nullToEmpty(record.getStatus()))) {
                continue;
            }
            if (!hasText(record.getPendingOrderId()) || !autoPendingIds.contains(record.getPendingOrderId())) {
                continue;
            }
            closedCount++;
            if (!"COMPLETE".equalsIgnoreCase(nullToEmpty(record.getPnlCompleteness()))
                    || record.getRealizedPnl() == null || record.getFee() == null
                    || record.getFundingFee() == null) {
                continue;
            }
            BigDecimal net = zeroIfNull(record.getRealizedPnl())
                    .add(zeroIfNull(record.getFee()))
                    .add(zeroIfNull(record.getFundingFee()));
            realized = realized.add(net);
            reconciledCount++;
            if (record.getCreatedAt() != null
                    && tradingDayResolver.tradingDate(record.getCreatedAt()).equals(today)) {
                todayRealized = todayRealized.add(net);
            }
        }
        return new CloseProfit(realized, todayRealized, closedCount, reconciledCount);
    }

    private List<PositionSummary> safePositions() {
        try {
            return positionSnapshotService.positions();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static BigDecimal effectiveMargin(PositionSummary position) {
        BigDecimal margin = zeroIfNull(position.margin());
        if (margin.signum() > 0) {
            return margin;
        }
        return zeroIfNull(position.initialMargin());
    }

    private static BigDecimal ratioPct(BigDecimal numerator, BigDecimal denominator) {
        if (numerator == null || denominator == null || denominator.signum() == 0) {
            return null;
        }
        return numerator.multiply(ONE_HUNDRED).divide(denominator, 8, RoundingMode.HALF_UP);
    }

    private static BigDecimal decimal(String value) {
        if (!hasText(value)) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(value).abs();
        } catch (NumberFormatException ex) {
            return BigDecimal.ZERO;
        }
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String nullToSystemUser(String value) {
        return hasText(value) ? value.trim() : OkxCredentialStore.SYSTEM_USER;
    }

    private static String currentUsername() {
        return AuthUserContext.currentUsername().orElse(OkxCredentialStore.SYSTEM_USER);
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private record CloseProfit(BigDecimal realizedNetPnl, BigDecimal todayRealizedNetPnl, int closedCount,
                               int reconciledCount) {
    }
}
