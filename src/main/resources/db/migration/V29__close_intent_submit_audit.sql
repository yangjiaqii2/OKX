ALTER TABLE close_position_record ADD COLUMN request_sent BOOLEAN;
ALTER TABLE close_position_record ADD COLUMN error_class VARCHAR(32);
ALTER TABLE close_position_record ADD COLUMN recovery_result VARCHAR(64);
ALTER TABLE close_position_record ADD COLUMN trade_plan_id VARCHAR(36);

CREATE INDEX idx_close_intent_cl_ord ON close_position_record (user_name, close_cl_ord_id);
