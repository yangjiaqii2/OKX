CREATE INDEX idx_quant_okx_credential_user_active
    ON quant_okx_credential (user_name, active, updated_at);
