-- V37: Selection V2 — Universe / Market Quality 字段
-- Phase B: MarketCap / FDV / Circulation / VolumeMcap 落库，供后续 Phase 评分与回溯
-- 业务阈值（marketCap / circulation / volumeMcap）配置在 application.yml 的 quant.agent.selection.universe，
-- 本 migration 只落 schema。
--
-- H2 v2 MySQL 模式限制：DECIMAL 精度 ≤ 18、ADD COLUMN 不支持多列。
-- 所以拆成 5 条独立 ALTER TABLE，precision 选 18。
ALTER TABLE agent_contract_candidate_score ADD COLUMN market_cap_usd DECIMAL(18, 4);
ALTER TABLE agent_contract_candidate_score ADD COLUMN fdv_usd DECIMAL(18, 4);
ALTER TABLE agent_contract_candidate_score ADD COLUMN circulation_ratio DECIMAL(8, 4);
ALTER TABLE agent_contract_candidate_score ADD COLUMN volume_mcap_ratio DECIMAL(8, 4);
ALTER TABLE agent_contract_candidate_score ADD COLUMN market_quality_note VARCHAR(255);
