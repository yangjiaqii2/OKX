ALTER TABLE trade_order
    ADD COLUMN user_name VARCHAR(128) NOT NULL DEFAULT 'local-admin' AFTER id;

CREATE INDEX idx_trade_order_user_status
    ON trade_order (user_name, status);
