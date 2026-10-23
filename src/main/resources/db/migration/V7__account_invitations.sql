-- 邀请凭证摘要仅用于核验，MFA种子采用应用层AES-GCM加密，密钥由部署Secret注入。
CREATE TABLE secret_material(id uuid PRIMARY KEY,ciphertext text NOT NULL,created_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE account_invitation(
 id uuid PRIMARY KEY,user_id uuid NOT NULL REFERENCES user_account(id),token_digest varchar(64) NOT NULL UNIQUE,
 expires_at timestamptz NOT NULL,consumed_at timestamptz,created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX invitation_user_idx ON account_invitation(user_id);
CREATE TABLE mfa_enrollment(
 id uuid PRIMARY KEY,invitation_id uuid NOT NULL UNIQUE REFERENCES account_invitation(id),secret_id uuid NOT NULL REFERENCES secret_material(id),
 expires_at timestamptz NOT NULL,consumed_at timestamptz
);
ALTER TABLE user_account ADD COLUMN activated_at timestamptz;
COMMENT ON COLUMN user_account.activated_at IS '邀请完成时间；未开通账户强制DISABLED，不能通过启停动作绕过激活';
UPDATE user_account SET activated_at=created_at WHERE status='ENABLED';
