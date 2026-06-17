package com.example.quant.controller;

import com.example.quant.order.OrderConfirmService;
import com.example.quant.order.OrderExecutionResult;
import java.math.BigDecimal;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/quant/orders")
public class TradeConfirmController {
    private static final Logger log = LoggerFactory.getLogger(TradeConfirmController.class);

    private final OrderConfirmService orderConfirmService;

    public TradeConfirmController(OrderConfirmService orderConfirmService) {
        this.orderConfirmService = orderConfirmService;
    }

    @PostMapping("/confirm")
    public Object confirm(@RequestParam UUID id, @RequestParam BigDecimal marginAmount) {
        log.info("HTTP confirm order id={} marginAmount={}", id, marginAmount);
        OrderExecutionResult result = orderConfirmService.confirm(id, marginAmount);
        if (!result.executed()) {
            throw new IllegalArgumentException(result.message());
        }
        return result;
    }
}
