ALTER TABLE agent_contract_candidate_score ADD COLUMN snapshot_stop_loss_price DECIMAL(30,12);
ALTER TABLE agent_contract_candidate_score ADD COLUMN snapshot_take_profit_price DECIMAL(30,12);
ALTER TABLE candidate_future_outcome ADD COLUMN directional_max_drawdown DECIMAL(18,12);
ALTER TABLE candidate_future_outcome ADD COLUMN first_barrier_touch VARCHAR(24);
