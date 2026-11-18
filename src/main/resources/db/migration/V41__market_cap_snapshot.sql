CREATE TABLE market_cap_snapshot (
    base_symbol VARCHAR(64) NOT NULL,
    market_cap_usd DECIMAL(38, 8) NULL,
    fdv_usd DECIMAL(38, 8) NULL,
    circulating_supply DECIMAL(38, 8) NULL,
    max_supply DECIMAL(38, 8) NULL,
    total_supply DECIMAL(38, 8) NULL,
    snapshot_at TIMESTAMP(6) NOT NULL,
    source VARCHAR(32) NOT NULL,
    PRIMARY KEY (base_symbol)
);
