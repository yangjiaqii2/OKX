package com.example.quant.account;

import com.example.quant.account.dto.PositionSummary;
import com.example.quant.okxtrade.OkxTradeAdapter;
import com.example.quant.order.OrderExecutionResult;
import java.math.BigDecimal;
import org.springframework.stereotype.Service;

@Service
public class PositionCloseService {
    private final PositionSnapshotService positionSnapshotService;
    private final OkxTradeAdapter okxTradeAdapter;

    public PositionCloseService(PositionSnapshotService positionSnapshotService, OkxTradeAdapter okxTradeAdapter) {
        this.positionSnapshotService = positionSnapshotService;
        this.okxTradeAdapter = okxTradeAdapter;
    }

    public OrderExecutionResult closePosition(String instId, String posSide, String marginMode) {
        if (!hasText(instId)) {
            throw new IllegalArgumentException("平仓合约不能为空");
        }
        PositionSummary position = positionSnapshotService.positions().stream()
                .filter(item -> instId.equalsIgnoreCase(item.instId()))
                .filter(item -> !hasText(posSide) || posSide.equalsIgnoreCase(item.posSide()))
                .filter(item -> decimal(item.size()).signum() > 0)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("当前没有可平仓持仓：" + instId));
        String closePosSide = hasText(posSide) ? posSide : position.posSide();
        String closeMarginMode = hasText(marginMode) ? marginMode : position.marginMode();
        if (!hasText(closeMarginMode)) {
            closeMarginMode = "cross";
        }
        return okxTradeAdapter.closePosition(position.instId(), closePosSide, closeMarginMode);
    }

    private static BigDecimal decimal(String value) {
        if (!hasText(value)) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(value).abs();
        } catch (NumberFormatException ex) {
            return BigDecimal.ZERO;
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
