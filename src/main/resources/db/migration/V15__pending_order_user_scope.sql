ALTER TABLE pending_order_state
    ADD COLUMN user_name VARCHAR(128) NOT NULL DEFAULT 'local-admin' AFTER id;

CREATE INDEX idx_pending_order_state_user_status
    ON pending_order_state (user_name, status);
