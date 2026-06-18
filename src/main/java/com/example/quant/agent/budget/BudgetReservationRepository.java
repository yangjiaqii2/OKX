package com.example.quant.agent.budget;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BudgetReservationRepository extends JpaRepository<BudgetReservationEntity, String> {
    Optional<BudgetReservationEntity> findByReservationId(String reservationId);

    Optional<BudgetReservationEntity> findFirstByPendingOrderId(String pendingOrderId);

    List<BudgetReservationEntity> findByStatusIn(Collection<String> statuses);
}
