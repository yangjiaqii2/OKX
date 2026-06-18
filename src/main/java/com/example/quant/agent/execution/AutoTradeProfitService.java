package com.example.quant.agent.execution;

import com.example.quant.account.OkxCredentialStore;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AutoTradeProfitService {
    private static final ZoneId DISPLAY_ZONE = ZoneId.of("Asia/Shanghai");
    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);

    private final AutoTradeRecordRepository recordRepository;
    private final PositionSnapshotService positionSnapshotService;
    private final SystemControlService systemControlService;
    private final Clock clock;

    @Autowired
    public AutoTradeProfitService(
            AutoTradeRecordRepository recordRepository,
            PositionSnapshotService positionSnapshotService,
            SystemControlService systemControlService
    ) {
        this(recordRepository, positionSnapshotService, systemControlService, Clock.system(DISPLAY_ZONE));
    }

    AutoTradeProfitService(
            AutoTradeRecordRepository recordRepository,
            PositionSnapshotService positionSnapshotService,
            SystemControlService systemControlService,
            Clock clock
    ) {
        this.recordRepository = recordRepository;
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
        BigDecimal realizedPnl = BigDecimal.ZERO;
        BigDecimal todayRealizedPnl = BigDecimal.ZERO;
        BigDecimal totalNetPnl = realizedPnl.add(unrealizedPnl);
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
                "ESTIMATED_UNREALIZED_ONLY",
                "当前为第一版收益概览：已实现收益暂按0处理，未实现收益来自OKX当前持仓；完整净收益需接入成交、手续费和资金费流水。"
        );
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
}
