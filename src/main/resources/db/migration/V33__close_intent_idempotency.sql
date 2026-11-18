ALTER TABLE close_position_record
    ADD CONSTRAINT uk_close_intent_owner_cl_ord UNIQUE (user_name, close_cl_ord_id);

ALTER TABLE close_position_record
    ADD CONSTRAINT uk_close_intent_owner_pending UNIQUE (user_name, pending_order_id);
