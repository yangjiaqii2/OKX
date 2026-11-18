package com.example.quant.agent.score;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentContractCandidateScoreRepository extends JpaRepository<AgentContractCandidateScoreEntity, Long> {
    List<AgentContractCandidateScoreEntity> findByScoringVersionOrderBySnapshotRankAsc(String scoringVersion);
    List<AgentContractCandidateScoreEntity> findByIdIn(Collection<Long> ids);
    long countByScanRunId(Long scanRunId);
}
