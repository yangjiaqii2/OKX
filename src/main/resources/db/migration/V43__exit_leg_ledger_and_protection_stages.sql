CREATE TABLE exit_leg_ledger (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 user_name VARCHAR(128) NOT NULL,
 pending_order_id VARCHAR(36) NOT NULL,
 trade_plan_id VARCHAR(36),
 inst_id VARCHAR(64) NOT NULL,
 exit_leg_id VARCHAR(32) NOT NULL,
 leg_type VARCHAR(24) NOT NULL,
 target_price DECIMAL(30,12),
 target_r DECIMAL(18,8),
 planned_quantity DECIMAL(30,12) NOT NULL DEFAULT 0,
 filled_quantity DECIMAL(30,12) NOT NULL DEFAULT 0,
 remaining_quantity DECIMAL(30,12) NOT NULL DEFAULT 0,
 status VARCHAR(32) NOT NULL,
 current_cl_ord_id VARCHAR(64),
 current_algo_id VARCHAR(128),
 created_at TIMESTAMP NOT NULL,
 updated_at TIMESTAMP NOT NULL,
 version BIGINT NOT NULL DEFAULT 0,
 CONSTRAINT uk_exit_leg_ledger_identity UNIQUE (user_name, pending_order_id, exit_leg_id)
);
CREATE INDEX idx_exit_leg_ledger_order ON exit_leg_ledger (user_name, pending_order_id, status);

ALTER TABLE position_exit_state ADD COLUMN observed_stage VARCHAR(32);
ALTER TABLE position_exit_state ADD COLUMN confirmed_protection_stage VARCHAR(32);
ALTER TABLE position_exit_state ADD COLUMN stop_amend_state VARCHAR(32) NOT NULL DEFAULT 'NONE';
