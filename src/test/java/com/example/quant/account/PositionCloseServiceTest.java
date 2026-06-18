package com.example.quant.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.quant.account.dto.PositionSummary;
import com.example.quant.okxtrade.OkxOrderGateway;
import com.example.quant.okxtrade.OkxTradeAdapter;
import com.example.quant.order.OrderExecutionResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PositionCloseServiceTest {

    @Test
    void closePositionPersistsCloseSubmittedRecordBeforeReturning() {
        ClosePositionRecordRepository repository = mock(ClosePositionRecordRepository.class);
        when(repository.save(any(ClosePositionRecordEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        PositionCloseService service = new PositionCloseService(
                new FixedPositionSnapshotService(),
                new OkxTradeAdapter(new CloseGateway()),
                repository
        );

        OrderExecutionResult result = service.closePosition("BTC-USDT-SWAP", "long", "cross");

        assertThat(result.executed()).isTrue();
        ArgumentCaptor<ClosePositionRecordEntity> captor = ArgumentCaptor.forClass(ClosePositionRecordEntity.class);
        verify(repository).save(captor.capture());
        ClosePositionRecordEntity record = captor.getValue();
        assertThat(record.getStatus()).isEqualTo("CLOSE_SUBMITTED");
        assertThat(record.getInstId()).isEqualTo("BTC-USDT-SWAP");
        assertThat(record.getPosSide()).isEqualTo("long");
        assertThat(record.getMarginMode()).isEqualTo("cross");
        assertThat(record.getCloseOrderId()).isEqualTo("close-ord-1");
        assertThat(record.getSource()).isEqualTo("MANUAL");
    }

    private static class FixedPositionSnapshotService extends PositionSnapshotService {
        FixedPositionSnapshotService() {
            super(null);
        }

        @Override
        public List<PositionSummary> positions() {
            return List.of(new PositionSummary(
                    "BTC-USDT-SWAP",
                    "long",
                    "2",
                    BigDecimal.valueOf(100),
                    BigDecimal.ZERO,
                    BigDecimal.TEN,
                    BigDecimal.TEN,
                    "cross",
                    BigDecimal.valueOf(2),
                    BigDecimal.ZERO,
                    BigDecimal.valueOf(200),
                    BigDecimal.ZERO,
                    "OKX_REAL"
            ));
        }
    }

    private static class CloseGateway implements OkxOrderGateway {
        @Override
        public JsonNode placeOrder(Map<String, String> payload) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public JsonNode closePosition(Map<String, String> payload) {
            ObjectNode root = new ObjectMapper().createObjectNode();
            root.putArray("data").addObject().put("ordId", "close-ord-1");
            return root;
        }
    }
}
