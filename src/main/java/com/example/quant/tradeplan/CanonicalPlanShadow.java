package com.example.quant.tradeplan;

import com.example.quant.order.PendingOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Read-only comparison between the legacy pending-order facts and a compiled
 * canonical plan. It never mutates an order, reserves risk, or calls OKX.
 */
public final class CanonicalPlanShadow {
    private CanonicalPlanShadow() {
    }

    public static List<String> divergences(PendingOrder order, CanonicalExecutablePlan compiled) {
        if (order == null || compiled == null) {
            return List.of("MISSING_INPUT");
        }
        List<String> result = new ArrayList<>();
        if (order.executableContractQuantity() != null && compiled.entry() != null
                && order.executableContractQuantity().compareTo(compiled.entry().executableContractQuantity()) != 0) {
            result.add("QTY_ROUNDING");
        }
        if (compiled.stopLoss() != null && order.stopLossPrice() != null
                && order.stopLossPrice().compareTo(compiled.stopLoss().normalizedPrice()) != 0) {
            result.add("PRICE_ROUNDING");
        }
        if (compiled.takeProfitLegs().isEmpty()) {
            result.add("TP_UNEXPRESSIBLE");
        } else if (order.takeProfitPrice() != null
                && compiled.takeProfitLegs().get(compiled.takeProfitLegs().size() - 1)
                .intendedTargetPrice().compareTo(order.takeProfitPrice()) != 0) {
            result.add("TP_SEMANTIC_DRIFT");
        }
        if (compiled.risk() != null && order.executableStopLossAmount() != null
                && compiled.risk().executableStopLossAmount() != null
                && order.executableStopLossAmount().compareTo(compiled.risk().executableStopLossAmount()) != 0) {
            result.add("RISK_MISMATCH");
        }
        return List.copyOf(result);
    }
}
