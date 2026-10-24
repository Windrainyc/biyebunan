-- 专用归属挑战与普通注册/重置挑战隔离，绑定指定订单、学生及预留手机号摘要。
CREATE TABLE order_binding_challenge (
 id uuid PRIMARY KEY,order_id uuid NOT NULL REFERENCES software_order(id) ON DELETE RESTRICT,
 actor_id uuid NOT NULL REFERENCES user_account(id) ON DELETE RESTRICT,
 phone_digest varchar(128) NOT NULL,code_digest varchar(128) NOT NULL,
 state varchar(16) NOT NULL CHECK(state IN ('PENDING','READY','FAILED','CONSUMED')),
 attempts integer NOT NULL DEFAULT 0 CHECK(attempts BETWEEN 0 AND 5),
 expires_at timestamptz NOT NULL,consumed_at timestamptz,created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX binding_challenge_scope_idx ON order_binding_challenge(actor_id,order_id,state);
COMMENT ON TABLE order_binding_challenge IS '失败次数必须在返回401前提交；消费、订单行锁归属和审计outbox必须同事务';
