package com.example.quant.okxtrade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.quant.agent.execution.TradeOrderEntity;
import com.example.quant.agent.execution.TradeOrderRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class OkxCurrentOrderSyncServiceTest {

    @Test
    void upsertsCurrentOkxNormalAndAlgoOrdersIntoTradeOrder() {
        TradeOrderRepository repository = mock(TradeOrderRepository.class);
        when(repository.findFirstByOkxOrdId(any())).thenReturn(Optional.empty());
        when(repository.findFirstByClOrdId(any())).thenReturn(Optional.empty());
        when(repository.save(any(TradeOrderEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        OkxCurrentOrderSyncService service = new OkxCurrentOrderSyncService(
                new OkxCurrentOrderService(new CurrentOrderGateway()),
                repository
        );

        OkxCurrentOrderSyncService.SyncResult result = service.syncOnce();

        assertThat(result.failed()).isFalse();
        assertThat(result.normalOrders()).isEqualTo(1);
        assertThat(result.algoOrders()).isEqualTo(1);
        assertThat(result.activeInstIds()).containsExactly("BTC-USDT-SWAP");
        ArgumentCaptor<TradeOrderEntity> captor = ArgumentCaptor.forClass(TradeOrderEntity.class);
        verify(repository, org.mockito.Mockito.times(2)).save(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(TradeOrderEntity::getOrderRole)
                .containsExactly("ENTRY", "STOP_LOSS");
        assertThat(captor.getAllValues())
                .extracting(TradeOrderEntity::getStatus)
                .containsExactly("SUBMITTED", "PROTECTION_SUBMITTED");
        assertThat(captor.getAllValues())
                .extracting(TradeOrderEntity::getOkxState)
                .containsExactly("live", "live");
    }

    @Test
    void updatesExistingLocalOrderByClientOrderId() {
        TradeOrderEntity existing = new TradeOrderEntity();
        existing.setOrderRole("ENTRY");
        existing.setInstId("BTC-USDT-SWAP");
        existing.setSide("buy");
        existing.setPosSide("long");
        existing.setOrdType("limit");
        existing.setTdMode("cross");
        existing.setReduceOnly(false);
        existing.setClOrdId("AUTOentry1");
        existing.setStatus("UNKNOWN_SUBMIT_STATUS");
        TradeOrderRepository repository = mock(TradeOrderRepository.class);
        when(repository.findFirstByOkxOrdId(any())).thenReturn(Optional.empty());
        when(repository.findFirstByClOrdId("AUTOentry1")).thenReturn(Optional.of(existing));
        when(repository.findFirstByClOrdId("qa123sl")).thenReturn(Optional.empty());
        when(repository.save(any(TradeOrderEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        OkxCurrentOrderSyncService service = new OkxCurrentOrderSyncService(
                new OkxCurrentOrderService(new CurrentOrderGateway()),
                repository
        );

        service.syncOnce();

        assertThat(existing.getOkxOrdId()).isEqualTo("ord-1");
        assertThat(existing.getStatus()).isEqualTo("SUBMITTED");
        assertThat(existing.getOkxState()).isEqualTo("live");
    }

    private static class CurrentOrderGateway implements OkxOrderGateway {
        @Override
        public JsonNode placeOrder(Map<String, String> payload) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public JsonNode currentOrders(Map<String, String> payload) {
            ObjectNode root = new ObjectMapper().createObjectNode();
            ObjectNode item = root.putArray("data").addObject();
            item.put("instId", "BTC-USDT-SWAP");
            item.put("ordId", "ord-1");
            item.put("clOrdId", "AUTOentry1");
            item.put("side", "buy");
            item.put("posSide", "long");
            item.put("ordType", "limit");
            item.put("tdMode", "cross");
            item.put("reduceOnly", "false");
            item.put("sz", "5");
            item.put("px", "100");
            item.put("state", "live");
            item.put("cTime", "1780000000000");
            return root;
        }

        @Override
        public JsonNode currentAlgoOrders(Map<String, String> payload) {
            ObjectNode root = new ObjectMapper().createObjectNode();
            ObjectNode item = root.putArray("data").addObject();
            item.put("instId", "BTC-USDT-SWAP");
            item.put("algoId", "algo-1");
            item.put("algoClOrdId", "qa123sl");
            item.put("side", "sell");
            item.put("posSide", "long");
            item.put("ordType", "conditional");
            item.put("tdMode", "cross");
            item.put("sz", "5");
            item.put("slTriggerPx", "98");
            item.put("state", "live");
            item.put("cTime", "1780000000000");
            return root;
        }
    }
}
