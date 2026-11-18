package com.example.quant.agent.exit;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExitLegLedgerRepository extends JpaRepository<ExitLegLedgerEntity, Long> {
    List<ExitLegLedgerEntity> findByUserNameAndPendingOrderIdOrderByExitLegId(String userName,
                                                                                String pendingOrderId);
    Optional<ExitLegLedgerEntity> findByUserNameAndPendingOrderIdAndExitLegId(String userName,
                                                                                String pendingOrderId,
                                                                                String exitLegId);
}
