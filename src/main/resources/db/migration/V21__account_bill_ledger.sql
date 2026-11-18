CREATE TABLE IF NOT EXISTS account_bill (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_name VARCHAR(128) NOT NULL,
    bill_id VARCHAR(128) NOT NULL,
    bill_type VARCHAR(16) NOT NULL,
    bill_sub_type VARCHAR(16) NOT NULL,
    category VARCHAR(32) NOT NULL,
    currency VARCHAR(32),
    inst_id VARCHAR(64),
    ord_id VARCHAR(128),
    cl_ord_id VARCHAR(64),
    trade_id VARCHAR(128),
    pnl DECIMAL(30, 12),
    fee DECIMAL(30, 12),
    balance_change DECIMAL(30, 12),
    bill_time TIMESTAMP NOT NULL,
    UNIQUE KEY uk_account_bill_owner (user_name, bill_id),
    INDEX idx_account_bill_time (user_name, bill_time),
    INDEX idx_account_bill_category (user_name, category)
);

CREATE TABLE IF NOT EXISTS account_ledger_sync_state (
    user_name VARCHAR(128) PRIMARY KEY,
    status VARCHAR(32) NOT NULL,
    synced_at TIMESTAMP,
    error_message VARCHAR(240)
);
