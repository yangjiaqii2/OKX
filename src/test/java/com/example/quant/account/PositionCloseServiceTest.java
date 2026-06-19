package com.example.quant.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.quant.account.dto.PositionSummary;
import com.example.quant.agent.budget.AutoTradeBudgetService;
import com.example.quant.agent.budget.BudgetAllocation;
import com.example.quant.agent.budget.BudgetAllocationRequest;
import com.example.quant.config.AgentProperties;
import com.example.quant.market.DirectionBias;
import com.example.quant.market.MarketType;
import com.example.quant.okxtrade.OkxOrderGateway;
import com.example.quant.okxtrade.OkxTradeAdapter;
import com.example.quant.order.PendingOrder;
import com.example.quant.order.PendingOrderService;
import com.example.quant.order.OrderExecutionResult;
import com.example.quant.tradeplan.TradePlan;
import com.example.quant.tradeplan.TradePlanType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

    @Test
    void manualCloseBindsMatchingAutoPendingOrder() {
        ClosePositionRecordRepository repository = mock(ClosePositionRecordRepository.class);
        when(repository.save(any(ClosePositionRecordEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        PendingOrderService pendingOrderService = new PendingOrderService(120);
        AutoTradeBudgetService budgetService = new AutoTradeBudgetService(new AgentProperties());
        PendingOrder order = autoOrder(pendingOrderService, budgetService);
        order.markProtectionSubmitted("PROTECTION_SUBMITTED");
        PositionCloseService service = new PositionCloseService(
                new FixedPositionSnapshotService(),
                new OkxTradeAdapter(new CloseGateway()),
                repository,
                pendingOrderService,
                null
        );

        service.closePosition("BTC-USDT-SWAP", "long", "cross");

        ArgumentCaptor<ClosePositionRecordEntity> captor = ArgumentCaptor.forClass(ClosePositionRecordEntity.class);
        verify(repository).save(captor.capture());
        ClosePositionRecordEntity record = captor.getValue();
        assertThat(record.getSource()).isEqualTo("MANUAL");
        assertThat(record.getPendingOrderId()).isEqualTo(order.id().toString());
    }

    @Test
    void closeRecordsUsesRepositoryCountForTotal() {
        ClosePositionRecordRepository repository = mock(ClosePositionRecordRepository.class);
        ClosePositionRecordEntity record = new ClosePositionRecordEntity();
        record.setUserName(OkxCredentialStore.SYSTEM_USER);
        record.setInstId("BTC-USDT-SWAP");
        record.setStatus("CLOSE_SUBMITTED");
        record.setSource("MANUAL");
        record.setCreatedAt(Instant.parse("2026-06-18T00:00:00Z"));
        record.setUpdatedAt(Instant.parse("2026-06-18T00:00:00Z"));
        when(repository.findByUserNameOrderByCreatedAtDesc(any(), any())).thenReturn(List.of(record));
        when(repository.countByUserName(OkxCredentialStore.SYSTEM_USER)).thenReturn(42L);
        PositionCloseService service = new PositionCloseService(
                new FixedPositionSnapshotService(),
                new OkxTradeAdapter(new CloseGateway()),
                repository
        );

        assertThat(service.closeRecords(0, 10).total()).isEqualTo(42);
    }

    private static PendingOrder autoOrder(PendingOrderService pendingOrderService, AutoTradeBudgetService budgetService) {
        TradePlan plan = new TradePlan(
                UUID.randomUUID(),
                "BTC-USDT-SWAP",
                TradePlanType.OPEN_LONG,
                "LIMIT",
                DirectionBias.BULLISH,
                BigDecimal.ONE,
                new BigDecimal("100"),
                new BigDecimal("98"),
                new BigDecimal("106"),
                2,
                BigDecimal.ONE,
                new BigDecimal("22.5"),
                new BigDecimal("100"),
                new BigDecimal("2"),
                90,
                BigDecimal.ZERO,
                BigDecimal.ONE,
                new BigDecimal("100000000"),
                "cross",
                List.of("test"),
                List.of(),
                "",
                true,
                Instant.now().plusSeconds(120)
        );
        BudgetAllocation allocation = budgetService.allocate(new BudgetAllocationRequest(
                new BigDecimal("50"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                1,
                new BigDecimal("92"),
                new BigDecimal("30"),
                new BigDecimal("2"),
                "LOW",
                "BULLISH",
                BigDecimal.ZERO,
                new BigDecimal("2.2")
        ));
        UUID pendingOrderId = UUID.randomUUID();
        return pendingOrderService.createAutoPendingOrder(
                MarketType.OKX_SWAP,
                plan,
                pendingOrderId,
                allocation.finalOrderMarginUsdt(),
                UUID.randomUUID(),
                allocation,
                "AUTO" + pendingOrderId.toString().replace("-", "").substring(0, 12)
        );
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
