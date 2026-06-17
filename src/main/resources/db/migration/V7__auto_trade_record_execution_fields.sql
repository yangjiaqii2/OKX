ALTER TABLE auto_trade_record ADD COLUMN action VARCHAR(32);
ALTER TABLE auto_trade_record ADD COLUMN pos_side VARCHAR(16);
ALTER TABLE auto_trade_record ADD COLUMN leverage INT;
ALTER TABLE auto_trade_record ADD COLUMN margin_amount DECIMAL(30, 12);
ALTER TABLE auto_trade_record ADD COLUMN entry_price DECIMAL(30, 12);
