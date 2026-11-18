ALTER TABLE trade_order ADD COLUMN filled_size DECIMAL(30, 12);
ALTER TABLE trade_order ADD COLUMN remaining_size DECIMAL(30, 12);
ALTER TABLE trade_order ADD COLUMN average_fill_price DECIMAL(30, 12);
ALTER TABLE trade_order ADD COLUMN okx_updated_at TIMESTAMP NULL;
