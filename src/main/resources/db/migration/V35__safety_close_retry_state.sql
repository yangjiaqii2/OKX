ALTER TABLE close_position_record
    ADD COLUMN retry_count INT NOT NULL DEFAULT 0;

ALTER TABLE close_position_record
    ADD COLUMN last_attempt_at TIMESTAMP NULL;

ALTER TABLE close_position_record
    ADD COLUMN next_attempt_at TIMESTAMP NULL;

CREATE INDEX idx_close_safety_retry
    ON close_position_record (user_name, source, status, next_attempt_at);
