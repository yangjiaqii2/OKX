package com.example.quant.agent.budget;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BudgetReservationRepository extends JpaRepository<BudgetReservationEntity, String> {
    Optional<BudgetReservationEntity> findByReservationId(String reservationId);

    Optional<BudgetReservationEntity> findByUserNameAndReservationId(String userName, String reservationId);

    Optional<BudgetReservationEntity> findFirstByPendingOrderId(String pendingOrderId);

    Optional<BudgetReservationEntity> findFirstByUserNameAndPendingOrderId(String userName, String pendingOrderId);

    List<BudgetReservationEntity> findByStatusIn(Collection<String> statuses);

    List<BudgetReservationEntity> findByUserNameAndStatusIn(String userName, Collection<String> statuses);
}
