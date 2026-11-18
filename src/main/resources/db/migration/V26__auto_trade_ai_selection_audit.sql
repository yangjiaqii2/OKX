ALTER TABLE auto_trade_record ADD COLUMN decision_source VARCHAR(32);
ALTER TABLE auto_trade_record ADD COLUMN ai_confidence DECIMAL(8, 6);
ALTER TABLE auto_trade_record ADD COLUMN ai_direction VARCHAR(16);
ALTER TABLE auto_trade_record ADD COLUMN ai_entry_mode VARCHAR(32);
ALTER TABLE auto_trade_record ADD COLUMN selection_reason TEXT;
