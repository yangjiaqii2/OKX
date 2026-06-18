package com.example.quant.controller;

import com.example.quant.account.AccountSnapshotService;
import com.example.quant.account.dto.OkxAccountVerificationResult;
import com.example.quant.system.AutoTradeRiskMode;
import com.example.quant.system.SystemControlService;
import java.math.BigDecimal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/quant/system")
public class SystemControlController {
    private final SystemControlService systemControlService;
    private final AccountSnapshotService accountSnapshotService;

    public SystemControlController(SystemControlService systemControlService, AccountSnapshotService accountSnapshotService) {
        this.systemControlService = systemControlService;
        this.accountSnapshotService = accountSnapshotService;
    }

    @GetMapping("/status")
    public Object status() {
        return systemControlService.status();
    }

    @PostMapping("/emergency-stop")
    public Object emergencyStop() {
        return systemControlService.emergencyStop();
    }

    @PostMapping("/resume")
    public Object resume() {
        return systemControlService.resume();
    }

    @PostMapping("/auto-trade/enable")
    public Object enableAutoTrade(@RequestParam(required = false) BigDecimal marginUsdt,
                                  @RequestParam(required = false) AutoTradeRiskMode riskMode,
                                  @RequestParam(required = false) Integer noRiskMinScore,
                                  @RequestParam(required = false) Integer minLeverage) {
        verifyOkxAccountBeforeEnable();
        if (riskMode == null) {
            return systemControlService.enableAutoTrade(marginUsdt);
        }
        return systemControlService.enableAutoTrade(marginUsdt, riskMode, noRiskMinScore, minLeverage);
    }

    @PostMapping("/auto-trade/disable")
    public Object disableAutoTrade() {
        return systemControlService.disableAutoTrade();
    }

    private void verifyOkxAccountBeforeEnable() {
        OkxAccountVerificationResult verification = accountSnapshotService.verifyOkx();
        if (verification == null || !verification.ok()) {
            String mode = verification == null ? "UNKNOWN" : verification.mode();
            String message = verification == null ? "OKX账户验证失败。" : verification.message();
            throw new IllegalStateException("开启自动交易前必须通过OKX Key验证：mode=" + mode + "，" + message);
        }
    }
}
