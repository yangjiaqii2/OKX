package com.example.quant.agent.evaluation;

import com.example.quant.account.OkxCredentialStore;
import com.example.quant.auth.AuthUserContext;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StrategyEvaluationService {
    private final ShadowSelectionRepository shadows;
    private final CounterfactualExitRepository exits;

    public StrategyEvaluationService(ShadowSelectionRepository shadows, CounterfactualExitRepository exits) {
        this.shadows = shadows; this.exits = exits;
    }

    @Transactional(readOnly = true)
    public Summary summary(String selectionPolicy, String exitPolicy) {
        String owner = AuthUserContext.currentUsername().orElse(OkxCredentialStore.SYSTEM_USER);
        List<ShadowSelectionEntity> selectionRows = shadows.findByUserNameOrderByCreatedAtAsc(owner).stream()
                .filter(row -> selectionPolicy == null || selectionPolicy.equals(row.getPolicy())).toList();
        int scanCount = (int) selectionRows.stream().map(ShadowSelectionEntity::getScanId).distinct().count();
        int selectionCount = (int) selectionRows.stream().filter(ShadowSelectionEntity::isSelected).count();
        BigDecimal coverage = scanCount == 0 ? null : BigDecimal.valueOf(selectionCount)
                .divide(BigDecimal.valueOf(scanCount), 8, RoundingMode.HALF_UP);
        List<CounterfactualExitEntity> tradeRows = exits.findByUserNameOrderByUpdatedAtAsc(owner).stream()
                .filter(row -> exitPolicy == null || exitPolicy.equals(row.getPolicy()))
                .filter(row -> "EXITED".equals(row.getStatus())).toList();
        int tradeCount = tradeRows.size();
        BigDecimal gross = sumComplete(tradeRows.stream().map(CounterfactualExitEntity::getGrossPnl).toList());
        BigDecimal fee = sumComplete(tradeRows.stream().map(CounterfactualExitEntity::getFee).toList());
        BigDecimal net = sumComplete(tradeRows.stream().map(CounterfactualExitEntity::getNetPnl).toList());
        BigDecimal expectancy = average(net, tradeCount);
        BigDecimal expectancyR = average(sumComplete(tradeRows.stream().map(CounterfactualExitEntity::getPnlR).toList()), tradeCount);
        BigDecimal profitFactor = profitFactor(tradeRows);
        BigDecimal drawdown = maxDrawdown(tradeRows);
        BigDecimal avgHold = tradeCount == 0 || tradeRows.stream().anyMatch(row -> row.getHoldMinutes() == null)
                ? null : tradeRows.stream().map(row -> BigDecimal.valueOf(row.getHoldMinutes()))
                .reduce(BigDecimal.ZERO, BigDecimal::add).divide(BigDecimal.valueOf(tradeCount), 8, RoundingMode.HALF_UP);
        String quality = scanCount == 0 && tradeCount == 0 ? "N/A"
                : tradeRows.stream().anyMatch(row -> row.getNetPnl() == null)
                ? "PARTIAL_FEE_UNAVAILABLE" : "COMPLETE";
        return new Summary(scanCount, selectionCount, coverage, tradeCount, gross, fee, net,
                expectancy, expectancyR, profitFactor, drawdown, avgHold, quality);
    }

    private static BigDecimal sumComplete(List<BigDecimal> values) {
        if (values.isEmpty() || values.stream().anyMatch(java.util.Objects::isNull)) return null;
        return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }
    private static BigDecimal average(BigDecimal total, int count) {
        return total == null || count == 0 ? null : total.divide(BigDecimal.valueOf(count), 8, RoundingMode.HALF_UP);
    }
    private static BigDecimal profitFactor(List<CounterfactualExitEntity> rows) {
        if (rows.isEmpty() || rows.stream().anyMatch(row -> row.getNetPnl() == null)) return null;
        BigDecimal gains = rows.stream().map(CounterfactualExitEntity::getNetPnl).filter(v -> v.signum() > 0)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal losses = rows.stream().map(CounterfactualExitEntity::getNetPnl).filter(v -> v.signum() < 0)
                .map(BigDecimal::abs).reduce(BigDecimal.ZERO, BigDecimal::add);
        return losses.signum() == 0 ? null : gains.divide(losses, 8, RoundingMode.HALF_UP);
    }
    private static BigDecimal maxDrawdown(List<CounterfactualExitEntity> rows) {
        if (rows.isEmpty() || rows.stream().anyMatch(row -> row.getNetPnl() == null)) return null;
        BigDecimal equity = BigDecimal.ZERO, peak = BigDecimal.ZERO, max = BigDecimal.ZERO;
        for (CounterfactualExitEntity row : rows) {
            equity = equity.add(row.getNetPnl()); peak = peak.max(equity); max = max.max(peak.subtract(equity));
        }
        return max;
    }

    public record Summary(int scanCount, int selectionCount, BigDecimal coverage, int tradeCount,
                          BigDecimal gross, BigDecimal fee, BigDecimal net, BigDecimal expectancy,
                          BigDecimal expectancyR, BigDecimal profitFactor, BigDecimal drawdown,
                          BigDecimal avgHold, String dataQuality) {}
}
