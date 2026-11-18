ALTER TABLE trade_order ADD COLUMN parent_algo_id VARCHAR(128);
ALTER TABLE trade_order ADD COLUMN pnl_completeness VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN';

ALTER TABLE close_position_record
    ADD COLUMN pnl_completeness VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN';

CREATE INDEX idx_trade_order_parent_algo
    ON trade_order (user_name, parent_algo_id);

CREATE INDEX idx_trade_order_pending_completeness
    ON trade_order (user_name, pending_order_id, pnl_completeness);

CREATE INDEX idx_close_pending_completeness
    ON close_position_record (user_name, pending_order_id, pnl_completeness);
