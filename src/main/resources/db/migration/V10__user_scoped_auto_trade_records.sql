ALTER TABLE auto_trade_record
    ADD COLUMN user_name VARCHAR(128) NOT NULL DEFAULT 'local-admin';

CREATE INDEX idx_auto_trade_record_user_status_created_at
    ON auto_trade_record (user_name, status, created_at);
