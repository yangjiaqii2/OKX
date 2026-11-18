package com.example.quant.agent.exit;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PositionExitStateRepository extends JpaRepository<PositionExitStateEntity, Long> {

    Optional<PositionExitStateEntity> findByUserNameAndPendingOrderId(String userName, String pendingOrderId);
}
