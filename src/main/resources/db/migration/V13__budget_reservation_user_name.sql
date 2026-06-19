ALTER TABLE auto_trade_budget_reservation
    ADD COLUMN user_name VARCHAR(128) NOT NULL DEFAULT 'local-admin' AFTER pending_order_id;

CREATE INDEX idx_auto_trade_budget_user_status
    ON auto_trade_budget_reservation (user_name, status);

CREATE INDEX idx_auto_trade_budget_user_pending
    ON auto_trade_budget_reservation (user_name, pending_order_id);
