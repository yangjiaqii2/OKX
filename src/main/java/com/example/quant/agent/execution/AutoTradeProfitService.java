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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
        List<TradeFillEntity> fills = tradeFillRepository == null ? List.of() : tradeFillRepository.findByUserName(username);
        Set<String> entryFilledLifecycles = entryFilledLifecycles(username, submittedRecords, fills);
        int executedCount = entryFilledLifecycles.size();
        Set<String> autoPendingIds = records.stream().map(AutoTradeRecordEntity::getPendingOrderId)
                .filter(AutoTradeProfitService::hasText).collect(java.util.stream.Collectors.toSet());
        CloseProfit closeProfit = realizedCloseProfit(username, today, autoPendingIds);
        BigDecimal submittedMargin = BigDecimal.ZERO;
        BigDecimal todaySubmittedMargin = BigDecimal.ZERO;
        for (AutoTradeRecordEntity record : records) {
            if (!"EXECUTED".equalsIgnoreCase(record.getStatus())
                    && !"ENTRY_SUBMITTED".equalsIgnoreCase(record.getStatus())) {
                continue;
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
        PositionPnl positionPnl = positionsAvailable
                ? positionPnl(positionSnapshot, submittedRecords, entryFilledLifecycles, closeProfit.closedPendingIds(), fills)
                : null;
        BigDecimal unrealizedPnl = positionPnl == null ? null : positionPnl.unrealized();
        BigDecimal usedMargin = positionPnl == null ? null : positionPnl.margin();
        int openPositionCount = positionPnl == null ? -1 : positionPnl.count();

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
        BigDecimal totalNetPnl = realizedAvailable && unrealizedPnl != null ? realizedPnl.add(unrealizedPnl) : null;
        String dataQuality = fundingUnattributed ? ledger.dataQuality()
                : ledgerAvailable ? ledger.dataQuality()
                : !realizedAvailable && closeProfit.closedCount() > 0
                ? "REALIZED_PNL_UNAVAILABLE"
                : closeProfit.reconciledCount() > 0 && closeProfit.reconciledCount() < closeProfit.closedCount()
                ? "PARTIAL_CLOSE_RECORD_ESTIMATED"
                : realizedAvailable ? "CLOSE_RECORD_ESTIMATED"
                : !positionsAvailable ? "POSITION_SNAPSHOT_UNAVAILABLE"
                : positionPnl == null ? "POSITION_ATTRIBUTION_UNAVAILABLE" : "ESTIMATED_UNREALIZED_ONLY";
        String message = fundingUnattributed
                ? "本系统可追溯 lifecycle 的毛收益与交易费来自OKX fills；资金费无法唯一归属 lifecycle，funding completeness=UNKNOWN，funding/net 为 N/A，不按0处理。"
                : ledgerAvailable
                ? "本系统可追溯订单的近期账本：毛收益与交易费来自OKX fills，资金费来自对应已管理合约的OKX bills；仅覆盖最近同步窗口，不代表完整账户历史。"
                : !realizedAvailable && closeProfit.closedCount() > 0
                ? "已实现收益不可用：平仓已确认，但尚未接入OKX fills/bill流水，不能把未知收益、手续费和资金费当作0；未实现收益来自已归属的OKX策略持仓。"
                : realizedAvailable
                ? "估算收益：已实现部分来自 close_position_record 的 realizedPnl + fee + fundingFee，未实现部分来自已归属的OKX策略持仓；完整真实净收益需接入OKX fills/bill流水。"
                : "估算收益：当前未找到已关闭平仓记录，未实现收益来自已归属的OKX策略持仓；完整真实净收益需接入成交均价、realizedPnl、fee、fundingFee和slippage。";
        if (!positionsAvailable) {
            message = "OKX持仓快照不可用。本系统未实现收益、策略持仓数和总净收益不可用。" + message;
        } else if (positionPnl == null) {
            message = "当前仓位无法完整归属到自动交易成交。本系统未实现收益、策略持仓数和总净收益不可用。" + message;
        }
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

    private static PositionPnl positionPnl(List<PositionSummary> positions, List<AutoTradeRecordEntity> submitted,
                                           Set<String> entryFilled, Set<String> closed, List<TradeFillEntity> fills) {
        Set<String> active = new HashSet<>();
        Set<String> activeInstruments = new HashSet<>();
        for (AutoTradeRecordEntity record : submitted) {
            if (closed.contains(record.getPendingOrderId()) || "CLOSED".equalsIgnoreCase(record.getStatus())) continue;
            if (entryFilled.contains(record.getPendingOrderId())) active.add(record.getPendingOrderId());
            activeInstruments.add(instrumentKey(record.getInstId()));
        }

        Map<ExposureKey, BigDecimal> exposure = new HashMap<>();
        for (TradeFillEntity fill : fills) {
            if (!active.contains(fill.getPendingOrderId())) continue;
            BigDecimal size = fill.getFillSize();
            if (!hasText(fill.getInstId()) || size == null || size.signum() <= 0) continue;
            String side = fill.getSide();
            if (!"buy".equalsIgnoreCase(side) && !"sell".equalsIgnoreCase(side)) continue;
            ExposureKey key = ExposureKey.of(fill.getInstId(), fill.getPosSide());
            boolean increase = "short".equals(key.side()) ? "sell".equalsIgnoreCase(side) : "buy".equalsIgnoreCase(side);
            exposure.merge(key, increase ? size : size.negate(), BigDecimal::add);
        }

        BigDecimal unrealized = BigDecimal.ZERO;
        BigDecimal margin = BigDecimal.ZERO;
        Set<ExposureKey> attributed = new HashSet<>();
        for (PositionSummary position : positions) {
            ExposureKey key = ExposureKey.of(position.instId(), position.posSide());
            if (!activeInstruments.contains(key.instrument())) continue;
            BigDecimal expected = exposure.get(key);
            BigDecimal size = parsePositionSize(position.size());
            if (size != null && size.signum() == 0) continue;
            if (expected == null) {
                if (!"UNMANAGED".equalsIgnoreCase(position.ownership())) return null;
                continue;
            }
            if (expected.signum() == 0) continue;
            if (!"MANAGED".equalsIgnoreCase(position.ownership()) || size == null
                    || position.unrealizedPnl() == null || !attributed.add(key)) return null;
            BigDecimal actual = "net".equals(key.side()) ? size : size.abs();
            if (expected.compareTo(actual) != 0) return null;
            unrealized = unrealized.add(position.unrealizedPnl());
            BigDecimal positionMargin = effectiveMargin(position);
            margin = margin == null || positionMargin == null ? null : margin.add(positionMargin);
        }
        return new PositionPnl(unrealized, margin, attributed.size());
    }

    private Set<String> entryFilledLifecycles(String username, List<AutoTradeRecordEntity> records, List<TradeFillEntity> fills) {
        Set<String> result = new HashSet<>();
        if (tradeOrderRepository == null) return result;
        Map<String, Set<String>> filledOrderIds = new HashMap<>();
        for (TradeFillEntity fill : fills) {
            if (!hasText(fill.getPendingOrderId()) || !hasText(fill.getOrdId())
                    || fill.getFillSize() == null || fill.getFillSize().signum() <= 0) continue;
            filledOrderIds.computeIfAbsent(fill.getPendingOrderId(), id -> new HashSet<>()).add(fill.getOrdId());
        }
        for (String pendingId : records.stream().map(AutoTradeRecordEntity::getPendingOrderId).distinct().toList()) {
            Set<String> filled = filledOrderIds.get(pendingId);
            if (filled != null && tradeOrderRepository.findByUserNameAndPendingOrderIdOrderByCreatedAtAsc(username, pendingId)
                    .stream().anyMatch(order -> !order.isReduceOnly() && filled.contains(order.getOkxOrdId()))) {
                result.add(pendingId);
            }
        }
        return result;
    }

    private record PositionPnl(BigDecimal unrealized, BigDecimal margin, int count) {}

    private record ExposureKey(String instrument, String side) {
        static ExposureKey of(String instrument, String side) {
            return new ExposureKey(instrumentKey(instrument), hasText(side) ? side.trim().toLowerCase(Locale.ROOT) : "net");
        }
    }

    private static String instrumentKey(String instrument) {
        return nullToEmpty(instrument).trim().toUpperCase(Locale.ROOT);
    }

    private static BigDecimal parsePositionSize(String size) {
        try {
            return hasText(size) ? new BigDecimal(size) : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private CloseProfit realizedCloseProfit(String username, LocalDate today, Set<String> autoPendingIds) {
        if (closePositionRecordRepository == null) {
            return new CloseProfit(BigDecimal.ZERO, BigDecimal.ZERO, 0, 0, Set.of());
        }
        BigDecimal realized = BigDecimal.ZERO;
        BigDecimal todayRealized = BigDecimal.ZERO;
        int closedCount = 0;
        int reconciledCount = 0;
        Set<String> closedPendingIds = new HashSet<>();
        for (ClosePositionRecordEntity record : closePositionRecordRepository
                .findByUserNameOrderByCreatedAtDesc(username, Pageable.unpaged())) {
            if (!"CLOSED".equalsIgnoreCase(nullToEmpty(record.getStatus()))) {
                continue;
            }
            if (!hasText(record.getPendingOrderId()) || !autoPendingIds.contains(record.getPendingOrderId())) {
                continue;
            }
            closedCount++;
            closedPendingIds.add(record.getPendingOrderId());
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
        return new CloseProfit(realized, todayRealized, closedCount, reconciledCount, closedPendingIds);
    }

    private List<PositionSummary> safePositions() {
        try {
            return positionSnapshotService.positions();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static BigDecimal effectiveMargin(PositionSummary position) {
        BigDecimal margin = position.margin();
        if (margin != null && (margin.signum() > 0 || position.initialMargin() == null)) {
            return margin;
        }
        return position.initialMargin();
    }

    private static BigDecimal ratioPct(BigDecimal numerator, BigDecimal denominator) {
        if (numerator == null || denominator == null || denominator.signum() == 0) {
            return null;
        }
        return numerator.multiply(ONE_HUNDRED).divide(denominator, 8, RoundingMode.HALF_UP);
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
                               int reconciledCount, Set<String> closedPendingIds) {
    }
}
