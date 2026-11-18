package com.example.quant.crypto;

import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OpenInterestService {
    private final OpenInterestSnapshotRepository repository;

    public OpenInterestService(OpenInterestSnapshotRepository repository) {
        this.repository = repository;
    }

    public String status() {
        return "open-interest-adapter-ready";
    }

    @Transactional
    public Observation observe(String instId, BigDecimal current, Instant observedAt) {
        if (instId == null || instId.isBlank() || current == null || current.signum() <= 0) {
            return new Observation(current, null, observedAt);
        }
        if (repository == null) {
            return new Observation(current, null, observedAt);
        }
        OpenInterestSnapshotEntity entity = repository.findById(instId).orElseGet(OpenInterestSnapshotEntity::new);
        BigDecimal previous = entity.getOpenInterest();
        entity.setInstId(instId);
        entity.setOpenInterest(current);
        entity.setObservedAt(observedAt == null ? Instant.now() : observedAt);
        repository.save(entity);
        return new Observation(current, previous, entity.getObservedAt());
    }

    public record Observation(BigDecimal current, BigDecimal previous, Instant observedAt) {
    }
}
