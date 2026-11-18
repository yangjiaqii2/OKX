-- Wave 5.1: durable retry metadata and explicit observed outcome window.
ALTER TABLE candidate_future_outcome ADD COLUMN retry_count INT NOT NULL DEFAULT 0;
ALTER TABLE candidate_future_outcome ADD COLUMN last_attempt_at TIMESTAMP NULL;
ALTER TABLE candidate_future_outcome ADD COLUMN next_retry_at TIMESTAMP NULL;
ALTER TABLE candidate_future_outcome ADD COLUMN observed_at TIMESTAMP NULL;
ALTER TABLE candidate_future_outcome ADD COLUMN window_start TIMESTAMP NULL;
ALTER TABLE candidate_future_outcome ADD COLUMN window_end TIMESTAMP NULL;
ALTER TABLE candidate_future_outcome ADD CONSTRAINT chk_candidate_future_outcome_horizon
    CHECK (horizon_minutes IN (60, 240, 1440));

CREATE INDEX idx_candidate_future_outcome_retry
    ON candidate_future_outcome (status, next_retry_at, maturity_at);
