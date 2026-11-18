ALTER TABLE pending_order_state
    ADD COLUMN executable_contract_quantity DECIMAL(30, 12);

ALTER TABLE pending_order_state
    ADD COLUMN executable_stop_loss_amount DECIMAL(30, 12);
