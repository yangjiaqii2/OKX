package com.example.quant.agent.audit;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentAuditLogRepository extends JpaRepository<AgentAuditLogEntity, Long> {
}
