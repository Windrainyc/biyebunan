-- TOTP 每个时间步最多使用一次；校验与登录会话创建在同一账户锁事务内完成。
ALTER TABLE user_account ADD COLUMN mfa_last_step bigint NOT NULL DEFAULT -1;
COMMENT ON COLUMN user_account.mfa_last_step IS '最后成功使用的 TOTP 时间步，防止同验证码并发重放';
