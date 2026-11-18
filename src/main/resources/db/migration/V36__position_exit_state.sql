-- Dynamic profit-protection state for one managed position.
--
-- This table exists because the protective stop is no longer a pure function of
-- the immutable canonical plan: once protection starts moving toward profit,
-- "where the stop is now" and "how much profit this trade once held" are facts
-- that cannot be reconstructed from the exchange alone (especially MFE, which
-- is only observable while the position is alive).
--
-- initial_stop_loss is stored here as an immutable fact. It is the source of R
-- for this trade and must never be overwritten by a protective stop move.

CREATE TABLE position_exit_state (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 user_name VARCHAR(128) NOT NULL,
 pending_order_id VARCHAR(36) NOT NULL,
 trade_plan_id VARCHAR(36),
 inst_id VARCHAR(64) NOT NULL,
 pos_side VARCHAR(16),

 entry_price DECIMAL(30,12) NOT NULL,
 initial_stop_loss DECIMAL(30,12) NOT NULL,
 initial_risk DECIMAL(30,12) NOT NULL,
 action VARCHAR(32) NOT NULL,

 highest_since_entry DECIMAL(30,12),
 lowest_since_entry DECIMAL(30,12),
 mfe_price DECIMAL(30,12),
 mae_price DECIMAL(30,12),
 mfe_pct DECIMAL(18,8),
 mae_pct DECIMAL(18,8),
 mfe_r DECIMAL(18,8),
 mae_r DECIMAL(18,8),
 mfe_at TIMESTAMP,
 mae_at TIMESTAMP,

 exit_state VARCHAR(32) NOT NULL,
 exit_state_since TIMESTAMP,

 applied_protective_stop DECIMAL(30,12),
 desired_protective_stop DECIMAL(30,12),
 stop_algo_cl_ord_id VARCHAR(32),
 stop_algo_id VARCHAR(128),
 stop_applied_at TIMESTAMP,
 stop_amend_count INT NOT NULL DEFAULT 0,
 stop_amend_window_start TIMESTAMP,
 pending_since TIMESTAMP,
 pending_req_id VARCHAR(32),

 last_mark_price DECIMAL(30,12),
 last_price_at TIMESTAMP,

 exit_reason VARCHAR(32),
 realized_r DECIMAL(18,8),
 mfe_r_final DECIMAL(18,8),
 giveback_r DECIMAL(18,8),
 giveback_ratio DECIMAL(18,8),
 closed_at TIMESTAMP,

 created_at TIMESTAMP NOT NULL,
 updated_at TIMESTAMP NOT NULL,
 version BIGINT NOT NULL DEFAULT 0,
 CONSTRAINT uk_position_exit_state_order UNIQUE (user_name, pending_order_id)
);
CREATE INDEX idx_position_exit_state_active ON position_exit_state (user_name, inst_id, exit_state);
