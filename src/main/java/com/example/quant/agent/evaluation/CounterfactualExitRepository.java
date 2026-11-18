package com.example.quant.agent.evaluation;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CounterfactualExitRepository extends JpaRepository<CounterfactualExitEntity, Long> {
    Optional<CounterfactualExitEntity> findByUserNameAndPendingOrderIdAndPolicy(
            String userName, String pendingOrderId, String policy);
    List<CounterfactualExitEntity> findByUserNameOrderByUpdatedAtAsc(String userName);
}
