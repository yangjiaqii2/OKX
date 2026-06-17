package com.example.quant.controller;

import com.example.quant.market.MarketType;
import com.example.quant.order.PendingOrderView;
import com.example.quant.order.PendingOrderService;
import com.example.quant.tradeplan.TradePlanService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/quant")
public class PendingOrderController {
    private final PendingOrderService pendingOrderService;
    private final TradePlanService tradePlanService;

    public PendingOrderController(PendingOrderService pendingOrderService, TradePlanService tradePlanService) {
        this.pendingOrderService = pendingOrderService;
        this.tradePlanService = tradePlanService;
    }

    @PostMapping("/contract/pending-order")
    public Object createContractPendingOrder(@RequestParam String instId) {
        return PendingOrderView.from(
                pendingOrderService.createPendingOrder(MarketType.OKX_SWAP, tradePlanService.createContractPlan(instId))
        );
    }

    @PostMapping("/contract/pending-order-batch")
    public Object createContractPendingOrderBatch(@RequestParam List<String> instIds) {
        List<PendingOrderView> results = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        String instId = instIds == null || instIds.isEmpty() ? "" : instIds.get(0);
        try {
            results.add(PendingOrderView.from(
                    pendingOrderService.createPendingOrder(MarketType.OKX_SWAP, tradePlanService.createContractPlan(instId))
            ));
        } catch (Exception ex) {
            errors.add(instId + ": " + ex.getMessage());
        }
        return new BatchPendingOrderResult(results, errors);
    }

    public record BatchPendingOrderResult(List<PendingOrderView> created, List<String> errors) {
    }

    @GetMapping("/orders/pending")
    public Object pending() {
        return pendingOrderService.pendingOrders().stream()
                .map(PendingOrderView::from)
                .toList();
    }

    @GetMapping("/orders/detail")
    public Object detail(@RequestParam UUID id) {
        return PendingOrderView.from(pendingOrderService.get(id));
    }

    @PostMapping("/orders/cancel")
    public Object cancel(@RequestParam UUID id) {
        pendingOrderService.cancel(id);
        return PendingOrderView.from(pendingOrderService.get(id));
    }
}
