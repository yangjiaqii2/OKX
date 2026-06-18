package com.example.quant.controller;

import com.example.quant.okxtrade.OkxCurrentOrderService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/quant/okx/orders")
public class OkxOrderController {
    private final OkxCurrentOrderService currentOrderService;

    public OkxOrderController(OkxCurrentOrderService currentOrderService) {
        this.currentOrderService = currentOrderService;
    }

    @GetMapping("/current")
    public Object currentOrders() {
        return currentOrderService.currentOrders();
    }

    @GetMapping("/algo")
    public Object currentAlgoOrders() {
        return currentOrderService.currentAlgoOrders();
    }
}
