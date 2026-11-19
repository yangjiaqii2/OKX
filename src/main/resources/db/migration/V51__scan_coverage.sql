CREATE TABLE scan_instrument_coverage (
    inst_id VARCHAR(64) PRIMARY KEY,
    attempted_at_ms BIGINT NOT NULL,
    analyzed_at_ms BIGINT NOT NULL
);
ALTER TABLE auto_trade_record ADD COLUMN scan_coverage VARCHAR(255);
