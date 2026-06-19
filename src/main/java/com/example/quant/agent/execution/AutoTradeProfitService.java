package com.example.quant.agent.execution;

import com.example.quant.account.OkxCredentialStore;
import com.example.quant.account.ClosePositionRecordEntity;
import com.example.quant.account.ClosePositionRecordRepository;
import com.example.quant.account.PositionSnapshotService;
import com.example.quant.account.dto.PositionSummary;
import com.example.quant.auth.AuthUserContext;
import com.example.quant.system.SystemControlService;
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
    private static final ZoneId DISPLAY_ZONE = ZoneId.of("Asia/Shanghai");
    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);

    private final AutoTradeRecordRepository recordRepository;
    private final ClosePositionRecordRepository closePositionRecordRepository;
    private final PositionSnapshotService positionSnapshotService;
    private final SystemControlService systemControlService;
    private final Clock clock;

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

    @Transactional(readOnly = true)
    public AutoTradeProfitSummary summary() {
        Instant now = clock.instant();
        String username = currentUsername();
        List<AutoTradeRecordEntity> records = recordRepository.findAll().stream()
                .filter(record -> "EXECUTED".equalsIgnoreCase(nullToEmpty(record.getStatus())))
                .filter(record -> username.equals(nullToSystemUser(record.getUserName())))
                .toList();
        Set<String> autoSymbols = new HashSet<>();
        BigDecimal submittedMargin = BigDecimal.ZERO;
        BigDecimal todaySubmittedMargin = BigDecimal.ZERO;
        LocalDate today = LocalDate.ofInstant(now, DISPLAY_ZONE);
        for (AutoTradeRecordEntity record : records) {
            if (hasText(record.getInstId())) {
                autoSymbols.add(record.getInstId().trim().toUpperCase());
            }
            BigDecimal margin = zeroIfNull(record.getMarginAmount());
            submittedMargin = submittedMargin.add(margin);
            if (record.getCreatedAt() != null
                    && LocalDate.ofInstant(record.getCreatedAt(), DISPLAY_ZONE).equals(today)) {
                todaySubmittedMargin = todaySubmittedMargin.add(margin);
            }
        }

        BigDecimal unrealizedPnl = BigDecimal.ZERO;
        BigDecimal usedMargin = BigDecimal.ZERO;
        int openPositionCount = 0;
        for (PositionSummary position : safePositions()) {
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

        BigDecimal totalBudget = zeroIfNull(systemControlService.status().autoTradeMarginUsdt());
        CloseProfit closeProfit = realizedCloseProfit(username, today);
        BigDecimal realizedPnl = closeProfit.realizedNetPnl();
        BigDecimal todayRealizedPnl = closeProfit.todayRealizedNetPnl();
        BigDecimal totalNetPnl = realizedPnl.add(unrealizedPnl);
        String dataQuality = closeProfit.closedCount() > 0
                ? "CLOSE_RECORD_ESTIMATED"
                : "ESTIMATED_UNREALIZED_ONLY";
        String message = closeProfit.closedCount() > 0
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
                records.size(),
                now,
                dataQuality,
                message
        );
    }

    private CloseProfit realizedCloseProfit(String username, LocalDate today) {
        if (closePositionRecordRepository == null) {
            return new CloseProfit(BigDecimal.ZERO, BigDecimal.ZERO, 0);
        }
        BigDecimal realized = BigDecimal.ZERO;
        BigDecimal todayRealized = BigDecimal.ZERO;
        int closedCount = 0;
        for (ClosePositionRecordEntity record : closePositionRecordRepository
                .findByUserNameOrderByCreatedAtDesc(username, Pageable.unpaged())) {
            if (!"CLOSED".equalsIgnoreCase(nullToEmpty(record.getStatus()))) {
                continue;
            }
            BigDecimal net = zeroIfNull(record.getRealizedPnl())
                    .add(zeroIfNull(record.getFee()))
                    .add(zeroIfNull(record.getFundingFee()));
            realized = realized.add(net);
            closedCount++;
            if (record.getCreatedAt() != null
                    && LocalDate.ofInstant(record.getCreatedAt(), DISPLAY_ZONE).equals(today)) {
                todayRealized = todayRealized.add(net);
            }
        }
        return new CloseProfit(realized, todayRealized, closedCount);
    }

    private List<PositionSummary> safePositions() {
        try {
            return positionSnapshotService.positions();
        } catch (RuntimeException ex) {
            return List.of();
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
        if (denominator == null || denominator.signum() == 0) {
            return BigDecimal.ZERO.setScale(8, RoundingMode.HALF_UP);
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

    private record CloseProfit(BigDecimal realizedNetPnl, BigDecimal todayRealizedNetPnl, int closedCount) {
    }
}
