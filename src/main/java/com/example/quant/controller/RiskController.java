package com.example.quant.controller;

import com.example.quant.config.TradingProperties;
import com.example.quant.system.SystemControlService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/quant/risk")
public class RiskController {
    private final SystemControlService systemControlService;
    private final TradingProperties tradingProperties;

    public RiskController(SystemControlService systemControlService, TradingProperties tradingProperties) {
        this.systemControlService = systemControlService;
        this.tradingProperties = tradingProperties;
    }

    @GetMapping("/status")
    public RiskRuntimeStatus status() {
        if (systemControlService.emergencyStopEnabled()) {
            return blocked("EMERGENCY_STOP", "紧急停止已开启");
        }
        if (tradingProperties == null || !tradingProperties.mode().writesEnabled()) {
            return blocked("TRADING_DISABLED", "OKX交易写入已禁用");
        }
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
