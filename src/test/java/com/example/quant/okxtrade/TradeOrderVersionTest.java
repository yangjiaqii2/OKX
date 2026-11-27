package com.example.quant.okxtrade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.example.quant.agent.execution.TradeOrderEntity;
import com.example.quant.agent.execution.TradeOrderRepository;
import com.example.quant.ledger.TradeFillLedgerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class TradeOrderVersionTest {
    @Autowired TradeOrderRepository repository;
    @Autowired PlatformTransactionManager transactions;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void staleRestTransactionCannotOverwriteCommittedWsFacts() throws Exception {
        TradeOrderEntity order = new TradeOrderEntity();
        order.setInstId("BTC-USDT-SWAP");
        order.setOrderRole("ENTRY");
        order.setSide("buy");
        order.setOrdType("limit");
        order.setTdMode("cross");
        order.setOkxOrdId("ord-optimistic-lock");
        order.setStatus("SUBMITTED");
        order.setOkxState("live");
        order.setCreatedAt(Instant.now());
        order.setUpdatedAt(order.getCreatedAt());
        Long id = repository.saveAndFlush(order).getId();
        var event = new ObjectMapper().readTree("""
                {"instId":"BTC-USDT-SWAP","ordId":"ord-optimistic-lock","state":"filled","sz":"10",
                 "accFillSz":"10","avgPx":"100","uTime":"2000","tradeId":"fill-1","fillSz":"10"}
                """);
        var ws = new OkxOrderEventService(repository, mock(TradeFillLedgerService.class));
        var rest = new TransactionTemplate(transactions);
        var push = new TransactionTemplate(transactions);
        push.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        try {
            assertThatThrownBy(() -> rest.executeWithoutResult(status -> {
                TradeOrderEntity stale = repository.findById(id).orElseThrow();
                push.executeWithoutResult(inner -> assertThat(ws.apply(event)).isTrue());
                stale.setFilledSize(BigDecimal.ZERO);
                stale.setOkxState("live");
                stale.setStatus("SUBMITTED");
                repository.saveAndFlush(stale);
            })).isInstanceOf(OptimisticLockingFailureException.class);
            TradeOrderEntity committed = repository.findById(id).orElseThrow();
            assertThat(committed.getStatus()).isEqualTo("FILLED");
            assertThat(committed.getFilledSize()).isEqualByComparingTo("10");
        } finally {
            repository.deleteById(id);
        }
    }
}
