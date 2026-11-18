package com.example.quant.controller;

import com.example.quant.demo.DemoTradeVerificationService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/quant/demo/verification")
public class DemoTradeVerificationController {
    private final DemoTradeVerificationService service;

    public DemoTradeVerificationController(DemoTradeVerificationService service) {
        this.service = service;
    }

    @PostMapping("/order")
    public Object submit(@RequestParam String instId) {
        return service.submit(instId);
    }
}
