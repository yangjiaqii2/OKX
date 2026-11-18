package com.example.quant.agent.execution;

import com.example.quant.account.PositionSideMatcher;
import com.example.quant.account.dto.PositionSummary;
import com.example.quant.config.AgentProperties;
import com.example.quant.crypto.dto.ContractCandidate;
import com.example.quant.market.DirectionBias;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class CorrelationExposureGuard {
    private final AgentProperties agentProperties;

    public CorrelationExposureGuard(AgentProperties agentProperties) {
        this.agentProperties = agentProperties;
    }

    public ExposureDecision evaluate(List<PositionSummary> positions, ContractCandidate candidate) {
        return evaluate(positions, candidate, candidate.trendDirection());
    }

    public ExposureDecision evaluate(List<PositionSummary> positions, ContractCandidate candidate,
                                     DirectionBias intendedDirection) {
        if (!agentProperties.exposure().enabled() || isMajor(candidate.instId())) {
            return ExposureDecision.allowed("exposure_guard_passed");
        }
        String candidateSide = intendedDirection == DirectionBias.BEARISH ? "short" : "long";
        long sameDirectionAltCount = (positions == null ? List.<PositionSummary>of() : positions).stream()
                .filter(position -> !isMajor(position.instId()))
                .filter(position -> PositionSideMatcher.matches(position.instId(), candidateSide, null, position))
                .count();
        int limit = agentProperties.exposure().maxSameDirectionAltPositions();
        if (candidate.marketRiskLevel().ordinal() >= com.example.quant.risk.RiskLevel.HIGH.ordinal()) {
            limit = Math.min(limit, agentProperties.exposure().maxSameDirectionAltPositionsHighVolatility());
        }
        if (sameDirectionAltCount >= limit) {
            return ExposureDecision.rejected("same_direction_alt_exposure_limit_" + limit);
        }
        return ExposureDecision.allowed("same_direction_alt_exposure_" + sameDirectionAltCount + "_of_" + limit);
    }

    private boolean isMajor(String instId) {
        return agentProperties.exposure().excludeMajorsFromAltCount().stream()
                .anyMatch(item -> item.equalsIgnoreCase(instId));
    }

    public record ExposureDecision(boolean allowed, String reason) {
        static ExposureDecision allowed(String reason) {
            return new ExposureDecision(true, reason);
        }

        static ExposureDecision rejected(String reason) {
            return new ExposureDecision(false, reason);
        }
    }
}
