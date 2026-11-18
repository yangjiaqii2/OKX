package com.example.quant.agent.entry;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WaitingEntryIntentRepository extends JpaRepository<WaitingEntryIntentEntity, String> {
    Optional<WaitingEntryIntentEntity> findByDecisionClientOrderId(String decisionClientOrderId);
    Optional<WaitingEntryIntentEntity> findByActiveKey(String activeKey);
    List<WaitingEntryIntentEntity> findByUserNameAndStatusInOrderByCreatedAtAsc(
            String userName, List<WaitingEntryIntentStatus> statuses);

    List<WaitingEntryIntentEntity> findByStatusIn(List<WaitingEntryIntentStatus> statuses);
}
