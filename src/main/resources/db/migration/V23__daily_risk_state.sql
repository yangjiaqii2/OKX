CREATE TABLE daily_risk_state (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_name VARCHAR(128) NOT NULL,
    trade_day DATE NOT NULL,
    starting_equity DECIMAL(30, 12) NOT NULL,
    entry_count INT NOT NULL DEFAULT 0,
    consecutive_losses INT NOT NULL DEFAULT 0,
    last_loss_at TIMESTAMP(6) NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_daily_risk_owner_day (user_name, trade_day)
);

CREATE TABLE daily_risk_entry_claim (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_name VARCHAR(128) NOT NULL,
    trade_day DATE NOT NULL,
    entry_key VARCHAR(128) NOT NULL,
    claimed_at TIMESTAMP(6) NOT NULL,
    outcome_recorded BOOLEAN NOT NULL DEFAULT FALSE,
    net_pnl DECIMAL(30, 12) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_daily_risk_entry (user_name, trade_day, entry_key)
);
