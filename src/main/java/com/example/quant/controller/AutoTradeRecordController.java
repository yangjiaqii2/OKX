package com.example.quant.controller;

import com.example.quant.agent.execution.AutoTradeRecordService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/quant/auto-trade")
public class AutoTradeRecordController {
    private final AutoTradeRecordService recordService;

    public AutoTradeRecordController(AutoTradeRecordService recordService) {
        this.recordService = recordService;
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
}
