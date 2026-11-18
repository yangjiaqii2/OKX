package com.example.quant.task;

import com.example.quant.agent.evaluation.CounterfactualExitService;
import com.example.quant.agent.execution.TradeOrderRepository;
import com.example.quant.auth.AuthUserContext;
import com.example.quant.crypto.OkxMarketService;
import com.example.quant.ledger.TradeFillEntity;
import com.example.quant.ledger.TradeFillRepository;
import com.example.quant.order.PendingOrder;
import com.example.quant.order.PendingOrderEntity;
import com.example.quant.order.PendingOrderRepository;
import com.example.quant.system.SystemControlService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class CounterfactualExitTask {
    private static final Logger log = LoggerFactory.getLogger(CounterfactualExitTask.class);
    private final PendingOrderRepository pendingOrders;
    private final TradeOrderRepository tradeOrders;
    private final TradeFillRepository fills;
    private final OkxMarketService market;
    private final CounterfactualExitService evaluator;
    private final SystemControlService controls;

    public CounterfactualExitTask(PendingOrderRepository pendingOrders, TradeOrderRepository tradeOrders,
                                  TradeFillRepository fills, OkxMarketService market,
                                  CounterfactualExitService evaluator, SystemControlService controls) {
        this.pendingOrders = pendingOrders; this.tradeOrders = tradeOrders; this.fills = fills;
        this.market = market; this.evaluator = evaluator; this.controls = controls;
    }

    @Scheduled(fixedRateString="${quant.agent.evaluation.interval-seconds:300}",
            initialDelayString="${quant.agent.evaluation.interval-seconds:300}", timeUnit=TimeUnit.SECONDS)
    public void runOnce() {
        String owner = controls.autoTradeOwnerUsername();
        if (owner == null || owner.isBlank()) return;
        AuthUserContext.runAs(owner, this::runAsOwner);
    }

    private void runAsOwner() {
        String owner = AuthUserContext.currentUsername().orElse("local-admin");
        for (PendingOrderEntity persisted : pendingOrders.findAll()) {
            if (!owner.equals(persisted.getUserName())) continue;
            PendingOrder order = persisted.toDomain();
            try {
                evaluate(order);
            } catch (RuntimeException ex) {
                log.warn("Counterfactual exit evaluation failed pendingOrderId={} message={}",
                        order.id(), ex.getMessage());
            }
        }
    }

    private void evaluate(PendingOrder order) {
        if (order.id() == null || order.submittedAt() == null) return;
        String owner = AuthUserContext.currentUsername().orElse("local-admin");
        String pendingId = order.id().toString();
        Set<String> entryOrderIds = tradeOrders
                .findByUserNameAndPendingOrderIdOrderByCreatedAtAsc(owner, pendingId).stream()
                .filter(row -> !row.isReduceOnly()).map(row -> row.getOkxOrdId())
                .filter(value -> value != null && !value.isBlank())
                .collect(java.util.stream.Collectors.toSet());
        var entryFills = fills.findByUserNameAndPendingOrderId(owner, pendingId).stream()
                .filter(fill -> entryOrderIds.contains(fill.getOrdId()))
                .filter(fill -> fill.getFillPrice() != null && fill.getFillSize() != null
                        && fill.getFillSize().signum() > 0 && fill.getFillTime() != null)
                .toList();
        if (entryFills.isEmpty()) return;
        BigDecimal quantity = entryFills.stream().map(TradeFillEntity::getFillSize)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal notional = entryFills.stream().map(fill -> fill.getFillPrice().multiply(fill.getFillSize()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal entryPrice = notional.divide(quantity, 12, RoundingMode.HALF_UP);
        Instant entryTime = entryFills.stream().map(TradeFillEntity::getFillTime)
                .min(Instant::compareTo).orElseThrow();
        BigDecimal riskDistance = entryPrice.subtract(order.stopLossPrice()).abs();
        if (riskDistance.signum() <= 0) return;
        evaluator.evaluateAndPersist(new CounterfactualExitService.Entry(
                pendingId, order.tradePlanId() == null ? null : order.tradePlanId().toString(), order.instId(),
                "short".equalsIgnoreCase(order.posSide()) ? "SHORT" : "LONG",
                entryTime, entryPrice, quantity, riskDistance, order.takeProfitPrice()),
                market.candles(order.instId(), "5m", 300));
    }
}
