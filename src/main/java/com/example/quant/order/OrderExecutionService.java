package com.example.quant.order;

import org.springframework.stereotype.Service;

@Service
public class OrderExecutionService {
    public OrderExecutionResult execute(PendingOrder order) {
        return new OrderExecutionResult(false, false, null, "OrderExecutionService requires explicit OKX adapter confirmation path");
    }
}
