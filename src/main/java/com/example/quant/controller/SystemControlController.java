package com.example.quant.controller;

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

    public SystemControlController(SystemControlService systemControlService) {
        this.systemControlService = systemControlService;
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
        if (riskMode == null) {
            return systemControlService.enableAutoTrade(marginUsdt);
        }
        return systemControlService.enableAutoTrade(marginUsdt, riskMode, noRiskMinScore, minLeverage);
    }

    @PostMapping("/auto-trade/disable")
    public Object disableAutoTrade() {
        return systemControlService.disableAutoTrade();
    }
}
