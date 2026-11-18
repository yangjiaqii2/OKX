ALTER TABLE system_control_state
    ADD COLUMN auto_trade_budget_mode VARCHAR(32) NOT NULL DEFAULT 'FIXED_USDT';

ALTER TABLE system_control_state
    ADD COLUMN auto_trade_margin_pct DECIMAL(8, 4) DEFAULT 0;
