package com.example.quant.controller;

import com.example.quant.account.AccountSnapshotService;
import com.example.quant.account.dto.AccountSummary;
import com.example.quant.agent.platform.AgentRunEntity;
import com.example.quant.agent.platform.AgentRunRepository;
import com.example.quant.agent.platform.TradingAgentEntity;
import com.example.quant.agent.platform.TradingAgentRepository;
import com.example.quant.agent.platform.TradingAgentStatus;
import com.example.quant.auth.AuthUserContext;
import com.example.quant.config.AgentProperties;
import com.example.quant.config.TradingProperties;
import com.example.quant.system.SystemControlService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/quant/risk")
public class RiskController {
    private final SystemControlService systemControlService;
    private final TradingProperties tradingProperties;
    private AccountSnapshotService accountSnapshotService;
    private TradingAgentRepository agentRepository;
    private AgentRunRepository agentRunRepository;
    private AgentProperties agentProperties;

    public RiskController(SystemControlService systemControlService, TradingProperties tradingProperties) {
        this.systemControlService = systemControlService;
        this.tradingProperties = tradingProperties;
    }

    @Autowired(required = false)
    void configureDiagnostics(
            AccountSnapshotService accountSnapshotService,
            TradingAgentRepository agentRepository,
            AgentRunRepository agentRunRepository,
            AgentProperties agentProperties
    ) {
        this.accountSnapshotService = accountSnapshotService;
        this.agentRepository = agentRepository;
        this.agentRunRepository = agentRunRepository;
        this.agentProperties = agentProperties;
    }

    @GetMapping("/status")
    public RiskRuntimeStatus status() {
        if (systemControlService.emergencyStopEnabled()) {
            return blocked("EMERGENCY_STOP", "紧急停止已开启");
        }
        if (tradingProperties == null || !tradingProperties.mode().writesEnabled()) {
            return blocked("TRADING_DISABLED", "OKX交易写入已禁用");
        }
        if (accountSnapshotService == null && agentRepository == null) {
            return new RiskRuntimeStatus(
                    null,
                    "NOT_EVALUATED",
                    "ORDER_CONTEXT_REQUIRED",
                    "订单级风控只在确认订单时按真实账户、订单簿和计划参数计算",
                    List.of("当前状态不是订单放行结论"),
                    null,
                    null,
                    null,
                    Instant.now()
            );
        }

        List<String> warnings = new ArrayList<>();
        boolean accountReady = true;
        BigDecimal equity = null;
        BigDecimal available = null;
        String accountReason = null;
        if (accountSnapshotService != null) {
            AccountSummary summary = accountSnapshotService.summary();
            String mode = summary == null || summary.mode() == null ? "" : summary.mode();
            equity = summary == null ? null : summary.equity();
            available = summary == null ? null : summary.availableBalance();
            if ("OKX_UNBOUND".equals(mode)) {
                accountReady = false;
                accountReason = "尚未绑定 OKX API 密钥";
            } else if ("OKX_ERROR".equals(mode)) {
                accountReady = false;
                accountReason = "OKX 账户接口异常：" + (summary.message() == null ? "无法读取余额" : summary.message());
            } else if (available == null || available.signum() <= 0) {
                accountReady = false;
                accountReason = "可用余额不足，暂无法开新仓";
            }
        }

        String owner = AuthUserContext.currentUsername().orElse(null);
        List<TradingAgentEntity> userAgents = (agentRepository != null && owner != null)
                ? agentRepository.findByUserNameOrderByUpdatedAtDesc(owner)
                : List.of();
        List<TradingAgentEntity> runningAgents = userAgents.stream()
                .filter(a -> a.getStatus() == TradingAgentStatus.RUNNING)
                .toList();

        Integer maxLeverage = runningAgents.stream()
                .map(TradingAgentEntity::getMaxLeverage)
                .max(Integer::compareTo)
                .orElse(agentProperties != null ? agentProperties.risk().maxLeverage() : 10);

        BigDecimal maxLossAmount = null;
        if (!runningAgents.isEmpty()) {
            maxLossAmount = runningAgents.stream()
                    .map(a -> a.getAllocatedBudgetUsdt()
                            .multiply(a.getMaxSingleLossPct())
                            .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP))
                    .max(BigDecimal::compareTo)
                    .orElse(null);
        } else if (equity != null && equity.signum() > 0 && agentProperties != null) {
            maxLossAmount = equity
                    .multiply(agentProperties.risk().singleTradeRiskPercent())
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        }

        Instant checkTime = Instant.now();
        String lastRunSummary = null;
        if (agentRunRepository != null && owner != null) {
            List<AgentRunEntity> latestRuns = agentRunRepository.findByUserNameOrderByCreatedAtDesc(owner, PageRequest.of(0, 1));
            AgentRunEntity latestRun = latestRuns.isEmpty() ? null : latestRuns.get(0);
            if (latestRun != null) {
                checkTime = latestRun.getCreatedAt();
                if ("ERROR".equals(latestRun.getDecision()) || "BLOCKED".equals(latestRun.getDecision())) {
                    warnings.add("最近一次 Agent 拦截：" + latestRun.getSummaryReason());
                } else {
                    lastRunSummary = "最近评估 " + latestRun.getDecision()
                            + (latestRun.getSelectedInstId() != null ? " (" + latestRun.getSelectedInstId() + ")" : "");
                }
            }
        }

        if (!accountReady) {
            return new RiskRuntimeStatus(
                    false,
                    "HIGH",
                    "ACCOUNT_NOT_READY",
                    accountReason,
                    warnings,
                    null,
                    maxLeverage,
                    maxLossAmount,
                    checkTime
            );
        }

        String summaryMsg = "运行中 Agent " + runningAgents.size() + " 个"
                + (lastRunSummary != null ? " · " + lastRunSummary : " · 账户余额与硬风控门禁正常");
        String riskLevel = warnings.isEmpty() ? "LOW" : "MEDIUM";
        return new RiskRuntimeStatus(
                true,
                riskLevel,
                null,
                warnings.isEmpty() ? summaryMsg : warnings.get(0),
                warnings,
                null,
                maxLeverage,
                maxLossAmount,
                checkTime
        );
    }

    private static RiskRuntimeStatus blocked(String code, String reason) {
        return new RiskRuntimeStatus(false, "BLOCKED", code, reason, List.of(), null, null, null, Instant.now());
    }

    public record RiskRuntimeStatus(
            Boolean passed,
            String riskLevel,
            String rejectCode,
            String rejectReason,
            List<String> warningList,
            BigDecimal adjustedSize,
            Integer adjustedLeverage,
            BigDecimal maxLossAmount,
            Instant createdAt
    ) {
    }
}
