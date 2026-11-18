CREATE TABLE open_interest_snapshot (
    inst_id VARCHAR(64) NOT NULL,
    open_interest DECIMAL(30, 12) NOT NULL,
    observed_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (inst_id)
);
