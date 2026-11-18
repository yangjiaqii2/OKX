CREATE TABLE notification_outbox (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    trade_event_id BIGINT NOT NULL,
    channel VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP(6) NOT NULL,
    lease_until TIMESTAMP(6) NULL,
    last_http_status INT NULL,
    last_error VARCHAR(512) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_notification_event_channel UNIQUE (trade_event_id, channel),
    INDEX idx_notification_due (status, next_attempt_at),
    CONSTRAINT fk_notification_trade_event FOREIGN KEY (trade_event_id) REFERENCES trade_event(id)
);
