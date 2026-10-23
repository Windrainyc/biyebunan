-- 用途隔离、验证码只保存密钥化摘要；供应商调用在数据库事务外。
CREATE TABLE verification_challenge (
 id uuid PRIMARY KEY, phone_digest varchar(64) NOT NULL,
 purpose varchar(24) NOT NULL CHECK(purpose IN ('REGISTER','RESET_PASSWORD','ACTIVATE','BIND_ORDER')),
 platform varchar(16) NOT NULL CHECK(platform IN ('student','partner','admin')),
 code_digest varchar(64) NOT NULL,
 state varchar(16) NOT NULL CHECK(state IN ('PENDING','READY','FAILED','CONSUMED')),
 attempts integer NOT NULL DEFAULT 0 CHECK(attempts BETWEEN 0 AND 5),
 expires_at timestamptz NOT NULL, consumed_at timestamptz,
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX challenge_phone_idx ON verification_challenge(phone_digest,purpose,created_at DESC);
COMMENT ON TABLE verification_challenge IS '验证码挑战；PENDING不是已发送，READY才允许消费，错误计数独立提交防止回滚绕过';
ALTER TABLE user_account ADD COLUMN terms_version varchar(200), ADD COLUMN phone_verified_at timestamptz;
COMMENT ON COLUMN user_account.terms_version IS '注册时明确同意的服务条款版本';
COMMENT ON COLUMN user_account.phone_verified_at IS '通过一次性手机挑战的时间，不接受客户端写入';
