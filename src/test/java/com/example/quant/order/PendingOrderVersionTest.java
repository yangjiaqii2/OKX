package com.example.quant.order;

import com.example.quant.market.MarketType;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class PendingOrderVersionTest {
    @Autowired PendingOrderRepository repository;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void staleProcessCannotCancelAnAcknowledgedOrderAndReloadsTheWinner() {
        var submitter = new PendingOrderService(120, repository);
        var order = submitter.createPendingOrder(MarketType.OKX_SWAP, PendingOrderServiceTest.samplePlan());
        var canceller = new PendingOrderService(120, repository);
        order.markSubmitted(Instant.now(), "exchange-ack");
        assertThatThrownBy(() -> canceller.cancel(order.id())).isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(canceller.get(order.id()).status()).isEqualTo(OrderStatus.SUBMITTED);
        assertThat(repository.findById(order.id().toString()).orElseThrow().toDomain().externalOrderId())
                .isEqualTo("exchange-ack");
    }
}
