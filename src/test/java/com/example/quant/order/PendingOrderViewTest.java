package com.example.quant.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.quant.market.MarketType;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class PendingOrderViewTest {
    @Test
    void pendingOrderViewSerializesWithoutConfirmToken() throws Exception {
        PendingOrderService service = new PendingOrderService(120);
        PendingOrder order = service.createPendingOrder(MarketType.OKX_SWAP, PendingOrderServiceTest.samplePlan());

        String json = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .writeValueAsString(PendingOrderView.from(order));

        assertThat(json).contains("\"id\"");
        assertThat(json).contains("\"instId\":\"BTC-USDT-SWAP\"");
        assertThat(json).contains("\"status\":\"PENDING_CONFIRM\"");
        assertThat(json).doesNotContain("confirmToken");
    }
}
