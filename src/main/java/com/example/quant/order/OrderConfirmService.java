package com.example.quant.order;

import com.example.quant.okxtrade.OkxTradeAdapter;
import com.example.quant.risk.ContractRiskRequest;
import com.example.quant.risk.RiskCheckResult;
import com.example.quant.risk.RiskService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class OrderConfirmService {
    private final PendingOrderService pendingOrderService;
    private final RiskService riskService;
    private final OkxTradeAdapter okxTradeAdapter;
    private final Clock clock;

    @Autowired
    public OrderConfirmService(PendingOrderService pendingOrderService, RiskService riskService, OkxTradeAdapter okxTradeAdapter) {
        this(pendingOrderService, riskService, okxTradeAdapter, Clock.systemUTC());
    }

    public OrderConfirmService(PendingOrderService pendingOrderService, RiskService riskService,
                               OkxTradeAdapter okxTradeAdapter, Clock clock) {
        this.pendingOrderService = pendingOrderService;
        this.riskService = riskService;
        this.okxTradeAdapter = okxTradeAdapter;
        this.clock = clock;
    }

    public OrderExecutionResult confirm(UUID orderId, BigDecimal marginAmount) {
        PendingOrder order = pendingOrderService.get(orderId);
        return confirm(orderId, marginAmount, riskRequestFor(order, marginAmount));
    }

    public OrderExecutionResult confirm(UUID orderId, BigDecimal marginAmount, ContractRiskRequest riskRequest) {
        PendingOrder order = pendingOrderService.get(orderId);
        Instant now = clock.instant();
        if (order.status() != OrderStatus.PENDING_CONFIRM) {
            return new OrderExecutionResult(false, false, null, "订单状态不允许确认");
        }
        if (order.isExpired(now)) {
            order.markExpired();
            return new OrderExecutionResult(false, false, null, "待确认订单已过期");
        }
        try {
            order.applyMarginAmount(marginAmount);
        } catch (IllegalArgumentException ex) {
            return new OrderExecutionResult(false, false, null, ex.getMessage());
        }
        RiskCheckResult risk = riskService.check(riskRequest);
        if (!risk.passed()) {
            order.markRejected(risk.rejectReason());
            return new OrderExecutionResult(false, false, null, risk.rejectReason());
        }
        order.markConfirmed(now);
        OrderExecutionResult result = okxTradeAdapter.placeOrder(order);
        if (result.executed()) {
            order.markExecuted(now);
        }
        return result;
    }

    private static ContractRiskRequest riskRequestFor(PendingOrder order, BigDecimal marginAmount) {
        ContractRiskRequest defaults = ContractRiskRequest.safeDefaults();
        return new ContractRiskRequest(
                defaults.emergencyStop(),
                defaults.websocketConnected(),
                defaults.marketDelayMs(),
                defaults.maxMarketDelayMs(),
                defaults.accountEquity(),
                marginAmount,
                defaults.maxSingleMarginRate(),
                order.leverage(),
                defaults.maxLeverage(),
                order.price(),
                order.stopLossPrice(),
                order.riskRewardRatio(),
                defaults.minRiskRewardRatio(),
                order.fundingRate(),
                defaults.maxAbsFundingRate(),
                order.volume24h(),
                defaults.minVolume24h(),
                defaults.newsRiskLevel(),
                order.volatility(),
                defaults.maxStopLossDistanceRate(),
                order.signalScore(),
                defaults.minSignalScore(),
                defaults.maxSingleLossRate()
        );
    }
}
