CREATE TABLE counterfactual_exit (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, user_name VARCHAR(128) NOT NULL,
 pending_order_id VARCHAR(36) NOT NULL, trade_plan_id VARCHAR(36), policy VARCHAR(24) NOT NULL,
 inst_id VARCHAR(64) NOT NULL, direction VARCHAR(8) NOT NULL, entry_time TIMESTAMP NOT NULL,
 entry_price DECIMAL(30,12) NOT NULL, quantity DECIMAL(30,12) NOT NULL,
 risk_distance DECIMAL(30,12) NOT NULL, exit_time TIMESTAMP, exit_price DECIMAL(30,12),
 gross_pnl DECIMAL(30,12), fee DECIMAL(30,12), net_pnl DECIMAL(30,12), pnl_r DECIMAL(18,8),
 hold_minutes BIGINT, status VARCHAR(16) NOT NULL, signal_venue VARCHAR(32) NOT NULL,
 execution_venue VARCHAR(32) NOT NULL, data_quality VARCHAR(64) NOT NULL, updated_at TIMESTAMP NOT NULL,
 CONSTRAINT uk_counterfactual_pending_policy UNIQUE (user_name, pending_order_id, policy)
);
CREATE INDEX idx_counterfactual_updated ON counterfactual_exit (user_name, updated_at);
