ALTER TABLE agent_contract_candidate_score ADD COLUMN analysis_score INT;
ALTER TABLE agent_contract_candidate_score ADD COLUMN analysis_action VARCHAR(32);
ALTER TABLE agent_contract_candidate_score ADD COLUMN scan_action VARCHAR(32);
