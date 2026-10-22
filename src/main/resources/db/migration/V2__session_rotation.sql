-- 令牌族持久化：刷新重放撤销整个族，不能因删除旧摘要而失去重放检测。
ALTER TABLE auth_session ADD COLUMN family_id uuid;
UPDATE auth_session SET family_id=id;
ALTER TABLE auth_session ALTER COLUMN family_id SET NOT NULL;
ALTER TABLE auth_session ADD COLUMN consumed_at timestamptz;
CREATE INDEX session_family_idx ON auth_session(family_id);
-- 验证码与邀请后续使用同一用途隔离原则，但不共用摘要域。
CREATE TABLE auth_attempt_window (
 bucket varchar(128) PRIMARY KEY, window_start timestamptz NOT NULL, attempts integer NOT NULL CHECK(attempts>0)
);
COMMENT ON TABLE auth_attempt_window IS '数据库认证限速窗口，多副本共享；按摘要存储，不包含明文手机号';
