package com.example.quant.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.quant.market.DirectionBias;
import com.example.quant.market.MarketType;
import com.example.quant.tradeplan.TradePlan;
import com.example.quant.tradeplan.TradePlanType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PendingOrderServiceTest {

    @Test
    void rejectsPendingOrderForAShareMarket() {
        PendingOrderService service = new PendingOrderService(120);

        assertThatThrownBy(() -> service.createPendingOrder(MarketType.A_SHARE, samplePlan()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("A股");
    }

    @Test
    void createsPendingOrderForOkxContractOnly() {
        PendingOrderService service = new PendingOrderService(120);

        PendingOrder order = service.createPendingOrder(MarketType.OKX_SWAP, samplePlan());

        assertThat(order.status()).isEqualTo(OrderStatus.PENDING_CONFIRM);
        assertThat(order.userConfirmed()).isFalse();
        assertThat(order.leverage()).isEqualTo(2);
        assertThat(order.maxLossAmount()).isEqualByComparingTo(BigDecimal.valueOf(100));
    }

    static TradePlan samplePlan() {
        return new TradePlan(
                UUID.randomUUID(),
                "BTC-USDT-SWAP",
                TradePlanType.OPEN_LONG,
                "LIMIT",
                DirectionBias.BULLISH,
                BigDecimal.valueOf(0.78),
                BigDecimal.valueOf(100),
                BigDecimal.valueOf(98),
                BigDecimal.valueOf(104),
                2,
                BigDecimal.valueOf(5),
                BigDecimal.valueOf(250),
                BigDecimal.valueOf(100),
                BigDecimal.valueOf(2),
                76,
                BigDecimal.valueOf(0.0004),
                BigDecimal.valueOf(0.025),
                BigDecimal.valueOf(25000000),
                "cross",
                List.of("test plan"),
                List.of("test risk"),
                "test invalid condition",
                true,
                Instant.now().plusSeconds(120)
        );
    }
}
