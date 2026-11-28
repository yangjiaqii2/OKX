package com.example.quant.agent.platform;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentRunRepository extends JpaRepository<AgentRunEntity, Long> {
    List<AgentRunEntity> findByAgentIdAndUserNameOrderByCreatedAtDesc(Long agentId, String userName, Pageable pageable);

    List<AgentRunEntity> findByUserNameOrderByCreatedAtDesc(String userName, Pageable pageable);
}
