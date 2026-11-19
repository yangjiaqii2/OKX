ALTER TABLE daily_risk_entry_claim ADD COLUMN reserved_loss_usdt DECIMAL(30,12);
ALTER TABLE pending_order_state ADD COLUMN state_version BIGINT NOT NULL DEFAULT 0;
