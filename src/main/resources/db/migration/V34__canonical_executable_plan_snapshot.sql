ALTER TABLE pending_order_state
    ADD COLUMN canonical_plan_json TEXT;

ALTER TABLE trade_plan
    ADD COLUMN canonical_plan_json TEXT;
