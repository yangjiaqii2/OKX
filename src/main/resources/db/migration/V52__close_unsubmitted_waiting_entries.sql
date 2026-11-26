UPDATE waiting_entry_intent
SET status = 'CANCELED',
    active_key = NULL,
    reason = 'AI_WAIT_DISABLED',
    updated_at = CURRENT_TIMESTAMP
WHERE status IN ('WAITING', 'TRIGGERED', 'VALIDATING')
  AND pending_order_id IS NULL
  AND okx_order_id IS NULL
  AND NOT EXISTS (
      SELECT 1
      FROM pending_order_state
      WHERE pending_order_state.client_order_id = waiting_entry_intent.decision_client_order_id
  );
