package com.example.quant.agent.platform;

import com.example.quant.crypto.UserPrivateWebSocketPool;
import java.time.Instant;
import java.util.List;

public record AdminAgentOverview(
        int totalAgents,
        int runningAgents,
        int activeUsers,
        boolean emergencyStopped,
        List<UserPrivateWebSocketPool.UserWsStatus> wsSessions,
        List<AdminAgentSummary> agents
) {
    public record AdminAgentSummary(
            Long id,
            String userName,
            String name,
            String status,
            String runMode,
            String lastDecision,
            String lastErrorCode,
            Instant lastRunAt
    ) {
        public static AdminAgentSummary from(TradingAgentEntity entity) {
            return new AdminAgentSummary(
                    entity.getId(),
                    entity.getUserName(),
                    entity.getName(),
                    entity.getStatus().name(),
                    entity.getRunMode().name(),
                    entity.getLastDecision(),
                    entity.getLastErrorCode(),
                    entity.getLastRunAt()
            );
        }
    }
}
