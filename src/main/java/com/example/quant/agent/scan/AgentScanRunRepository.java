package com.example.quant.agent.scan;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AgentScanRunRepository extends JpaRepository<AgentScanRunEntity, Long> {
    @Query("""
            select r from AgentScanRunEntity r
            where r.status = 'SUCCESS' and r.candidateCount > 0
              and exists (select s.id from AgentContractCandidateScoreEntity s
                          where s.scanRunId = r.id and s.scoringVersion = :version)
              and r.candidateCount = (select count(s) from AgentContractCandidateScoreEntity s
                                      where s.scanRunId = r.id)
            """)
    List<AgentScanRunEntity> findCompleteForVersion(@Param("version") String version);
}
