package com.example.quant.agent.execution;

import com.example.quant.agent.execution.AutoTradeService.AutoTradeResult;
import com.example.quant.account.OkxCredentialStore;
import com.example.quant.auth.AuthUserContext;
import com.example.quant.common.PageResult;
import com.example.quant.crypto.dto.ContractCandidate;
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AutoTradeRecordService {
    private static final Logger log = LoggerFactory.getLogger(AutoTradeRecordService.class);

    private final AutoTradeRecordRepository repository;

    public AutoTradeRecordService(AutoTradeRecordRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void record(AutoTradeResult result, int candidateCount, ContractCandidate candidate) {
        if (!"EXECUTED".equals(result.status())) {
            return;
        }
        try {
            AutoTradeRecordEntity entity = new AutoTradeRecordEntity();
            entity.setStatus(result.status());
            entity.setUserName(currentUsername());
            entity.setTriggerType("SCHEDULER");
            entity.setInstId(result.instId() != null ? result.instId() : candidate == null ? null : candidate.instId());
            entity.setTradePlanId(result.tradePlanId());
            entity.setPendingOrderId(result.pendingOrderId());
            entity.setOkxOrderId(result.okxOrderId());
            entity.setAction(result.action());
            entity.setPosSide(result.posSide());
            entity.setLeverage(result.leverage());
            entity.setMarginAmount(result.marginAmount());
            entity.setEntryPrice(result.entryPrice());
            entity.setCandidateCount(candidateCount);
            if (candidate != null) {
                entity.setCandidateScore(candidate.score());
                entity.setTrendDirection(candidate.trendDirection() == null ? null : candidate.trendDirection().name());
                entity.setLastPrice(candidate.lastPrice());
                entity.setRiskRewardRatio(candidate.riskRewardRatio());
                entity.setSpreadBps(candidate.spreadBps());
                entity.setBidDepthUsdt(candidate.bidDepthUsdt());
                entity.setAskDepthUsdt(candidate.askDepthUsdt());
                entity.setMarketRiskLevel(candidate.marketRiskLevel() == null ? null : candidate.marketRiskLevel().name());
            }
            entity.setMessage(result.message());
            entity.setCreatedAt(result.createdAt() == null ? Instant.now() : result.createdAt());
            repository.save(entity);
        } catch (RuntimeException ex) {
            log.warn("Failed to persist auto trade record status={} instId={} message={}",
                    result.status(), result.instId(), ex.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public PageResult<AutoTradeRecordView> list(String status, String instId, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, 200));
        Page<AutoTradeRecordEntity> result = repository.findAll(spec(status, instId),
                PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "createdAt")));
        return new PageResult<>(
                result.getContent().stream().map(AutoTradeRecordView::from).toList(),
                result.getTotalElements(),
                safePage,
                safeSize
        );
    }

    private static Specification<AutoTradeRecordEntity> spec(String status, String instId) {
        return (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (hasText(status)) {
                predicates.add(builder.equal(root.get("status"), status.trim()));
            } else {
                predicates.add(builder.equal(root.get("status"), "EXECUTED"));
            }
            predicates.add(builder.equal(root.get("userName"), currentUsername()));
            if (hasText(instId)) {
                predicates.add(builder.like(builder.lower(root.get("instId")), "%" + instId.trim().toLowerCase() + "%"));
            }
            return predicates.isEmpty() ? builder.conjunction() : builder.and(predicates.toArray(Predicate[]::new));
        };
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static String currentUsername() {
        return AuthUserContext.currentUsername().orElse(OkxCredentialStore.SYSTEM_USER);
    }
}
