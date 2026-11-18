package com.example.quant.account;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class ClosePositionRecordRepositoryTest {
    @Autowired
    private ClosePositionRecordRepository repository;

    @Test
    void stableCloseClientOrderIdIsUniquePerOwner() {
        repository.saveAndFlush(intent("alice", "MAXC-stable", null));

        assertThatThrownBy(() -> repository.saveAndFlush(intent("alice", "MAXC-stable", null)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void managedPendingOrderCanOwnOnlyOneCloseIntent() {
        repository.saveAndFlush(intent("bob", "MANUAL-close", "pending-1"));

        assertThatThrownBy(() -> repository.saveAndFlush(intent("bob", "MAXC-close", "pending-1")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private static ClosePositionRecordEntity intent(String owner, String closeClOrdId, String pendingOrderId) {
        Instant now = Instant.parse("2026-09-07T09:00:00Z");
        ClosePositionRecordEntity record = new ClosePositionRecordEntity();
        record.setUserName(owner);
        record.setInstId("BTC-USDT-SWAP");
        record.setCloseClOrdId(closeClOrdId);
        record.setPendingOrderId(pendingOrderId);
        record.setStatus("CLOSE_SUBMITTING");
        record.setSource("MAX_HOLD_TIMEOUT");
        record.setCreatedAt(now);
        record.setUpdatedAt(now);
        return record;
    }
}
