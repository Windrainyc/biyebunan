-- 核验是管理员确认动作；未提供的实付资料保留空值，不生成虚假流水或金额。
ALTER TABLE order_receipt ALTER COLUMN reference DROP NOT NULL;
ALTER TABLE order_receipt ALTER COLUMN received_at DROP NOT NULL;
ALTER TABLE order_receipt ALTER COLUMN amount DROP NOT NULL;
ALTER TABLE order_receipt ALTER COLUMN proof_asset_id DROP NOT NULL;
ALTER TABLE order_receipt ALTER COLUMN reason DROP NOT NULL;
ALTER TABLE order_receipt DROP CONSTRAINT IF EXISTS order_receipt_reason_check;
ALTER TABLE order_receipt ADD COLUMN channel varchar(16) CHECK(channel IN ('WECHAT','ALIPAY','BANK_CARD'));
COMMENT ON COLUMN order_receipt.amount IS '可选实付金额，缺失不代表零元或订单面额';
