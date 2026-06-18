package com.example.quant.controller;

import com.example.quant.agent.execution.AutoTradeRecordService;
import com.example.quant.agent.execution.AutoTradeProfitService;
import com.example.quant.agent.lifecycle.AutoTradeLifecycleService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/quant/auto-trade")
public class AutoTradeRecordController {
    private final AutoTradeRecordService recordService;
    private final AutoTradeProfitService profitService;
    private final AutoTradeLifecycleService lifecycleService;

    public AutoTradeRecordController(AutoTradeRecordService recordService, AutoTradeProfitService profitService,
                                     AutoTradeLifecycleService lifecycleService) {
        this.recordService = recordService;
        this.profitService = profitService;
        this.lifecycleService = lifecycleService;
    }

    @GetMapping("/records")
    public Object records(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String instId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size
    ) {
        return recordService.list(status, instId, page, size);
    }

    @GetMapping("/profit/summary")
    public Object profitSummary() {
        return profitService.summary();
    }

    @GetMapping("/lifecycle")
    public Object lifecycle() {
        return lifecycleService.snapshots();
    }
}
