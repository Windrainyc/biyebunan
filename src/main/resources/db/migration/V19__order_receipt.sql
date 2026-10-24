-- 人工核验收款快照：仅支持订单全额一次确认，不冒充支付网关回调或退款账本。
CREATE TABLE order_receipt (
 order_id uuid PRIMARY KEY REFERENCES software_order(id),
 reference varchar(200) NOT NULL UNIQUE CHECK(length(trim(reference))>0),
 received_at timestamptz NOT NULL,
 amount numeric(20,6) NOT NULL CHECK(amount>0),
 currency varchar(3) NOT NULL CHECK(currency='CNY'),
 proof_asset_id uuid NOT NULL UNIQUE REFERENCES asset(id),
 reason varchar(1000) NOT NULL CHECK(length(trim(reason))>0),
 confirmed_by uuid NOT NULL REFERENCES user_account(id),
 confirmed_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE order_receipt IS '人工确认快照；订单锁后资产锁；凭证引用后不得删除，流水号全局唯一防重复入账';
CREATE INDEX order_receipt_confirmed_idx ON order_receipt(confirmed_at DESC,order_id);
