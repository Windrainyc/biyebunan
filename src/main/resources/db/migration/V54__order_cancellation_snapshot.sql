-- 取消快照不等于退款；已确认收款必须保留财务核对义务，原收款和模型账本不变。
CREATE TABLE order_cancellation (
 order_id uuid PRIMARY KEY REFERENCES software_order(id),
 previous_status varchar(24) NOT NULL,
 financial_review_required boolean NOT NULL,
 reason varchar(200) NOT NULL,
 actor_id uuid NOT NULL REFERENCES user_account(id),
 created_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE order_cancellation IS '订单行锁内写入不可变取消快照；阻止新模型运行后检查未决预占，不释放未知费用';

CREATE FUNCTION protect_order_cancellation() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 RAISE EXCEPTION 'order cancellation is immutable' USING ERRCODE='23514';
END; $$;
CREATE TRIGGER order_cancellation_immutable BEFORE UPDATE OR DELETE ON order_cancellation FOR EACH ROW EXECUTE FUNCTION protect_order_cancellation();
