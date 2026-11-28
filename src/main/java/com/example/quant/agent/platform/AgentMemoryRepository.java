package com.example.quant.agent.platform;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentMemoryRepository extends JpaRepository<AgentMemoryEntity, Long> {
    List<AgentMemoryEntity> findByAgentIdAndUserNameOrderByPinnedDescUpdatedAtDesc(Long agentId, String userName);

    List<AgentMemoryEntity> findByAgentIdAndUserNameAndEnabledTrueOrderByPinnedDescUpdatedAtDesc(Long agentId, String userName);

    Optional<AgentMemoryEntity> findByIdAndAgentIdAndUserName(Long id, Long agentId, String userName);
}
