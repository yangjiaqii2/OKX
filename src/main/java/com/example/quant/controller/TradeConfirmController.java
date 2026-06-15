package com.example.quant.controller;

import com.example.quant.order.OrderConfirmService;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/quant/orders")
public class TradeConfirmController {
    private final OrderConfirmService orderConfirmService;

    public TradeConfirmController(OrderConfirmService orderConfirmService) {
        this.orderConfirmService = orderConfirmService;
    }

    @PostMapping("/confirm")
    public Object confirm(@RequestParam UUID id, @RequestParam BigDecimal marginAmount) {
        return orderConfirmService.confirm(id, marginAmount);
    }
}
