-- 身份及基础数据：只创建知项平台业务表，不删除本地环境已有数据。
CREATE TABLE school (
 id uuid PRIMARY KEY, version bigint NOT NULL DEFAULT 1 CHECK(version > 0),
 name varchar(200) NOT NULL CHECK(length(trim(name)) > 0),
 code varchar(200) NOT NULL UNIQUE, region varchar(200) NOT NULL,
 status varchar(16) NOT NULL CHECK(status IN ('ENABLED','DISABLED')),
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE school IS '院校字典；无引用才允许物理删除，有历史引用仅停用';
CREATE INDEX school_page_idx ON school(created_at DESC,id DESC);
CREATE TABLE major (
 id uuid PRIMARY KEY, version bigint NOT NULL DEFAULT 1 CHECK(version > 0),
 school_id uuid NOT NULL REFERENCES school(id) ON DELETE RESTRICT,
 name varchar(200) NOT NULL, code varchar(200) NOT NULL,
 level varchar(16) NOT NULL CHECK(level IN ('COLLEGE','BACHELOR','MASTER')),
 status varchar(16) NOT NULL CHECK(status IN ('ENABLED','DISABLED')),
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(school_id,code), UNIQUE(id,school_id)
);
COMMENT ON TABLE major IS '专业字典；专业代码在院校内唯一';
CREATE INDEX major_page_idx ON major(school_id,created_at DESC,id DESC);
CREATE TABLE user_account (
 id uuid PRIMARY KEY, version bigint NOT NULL DEFAULT 1,
 platform varchar(16) NOT NULL CHECK(platform IN ('student','partner','admin')),
 display_name varchar(200) NOT NULL,
 phone_digest varchar(64) NOT NULL, phone_masked varchar(32) NOT NULL,
 password_hash varchar(255) NOT NULL, status varchar(16) NOT NULL CHECK(status IN ('ENABLED','DISABLED')),
 auth_version bigint NOT NULL DEFAULT 1, school_id uuid REFERENCES school(id), major_id uuid,
 partner_id uuid REFERENCES user_account(id), mfa_secret_ref varchar(200),
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(platform,phone_digest), FOREIGN KEY(major_id,school_id) REFERENCES major(id,school_id),
 CHECK(major_id IS NULL OR school_id IS NOT NULL)
);
COMMENT ON COLUMN user_account.phone_digest IS '密钥化 HMAC-SHA256 摘要；不可用手机号明文或无密钥哈希';
CREATE TABLE role (
 id uuid PRIMARY KEY, code varchar(200) NOT NULL UNIQUE, name varchar(200) NOT NULL,
 platform varchar(16) NOT NULL CHECK(platform IN ('student','partner','admin')),
 data_scope varchar(16) NOT NULL CHECK(data_scope IN ('SELF','OWNED','ALL')),
 status varchar(16) NOT NULL CHECK(status IN ('ENABLED','DISABLED')),
 built_in boolean NOT NULL DEFAULT false, version bigint NOT NULL DEFAULT 1,
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE role_permission(role_id uuid REFERENCES role(id) ON DELETE CASCADE,permission_code varchar(200) NOT NULL,PRIMARY KEY(role_id,permission_code));
CREATE TABLE user_role(user_id uuid REFERENCES user_account(id) ON DELETE RESTRICT,role_id uuid REFERENCES role(id) ON DELETE RESTRICT,PRIMARY KEY(user_id,role_id));
CREATE TABLE auth_session (
 id uuid PRIMARY KEY, user_id uuid NOT NULL REFERENCES user_account(id),
 access_digest varchar(64) NOT NULL UNIQUE, refresh_digest varchar(64) NOT NULL UNIQUE,
 csrf_digest varchar(64) NOT NULL, auth_version bigint NOT NULL,
 expires_at timestamptz NOT NULL, refresh_expires_at timestamptz NOT NULL,
 revoked_at timestamptz, created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX session_user_idx ON auth_session(user_id);
CREATE TABLE audit_event (
 id uuid PRIMARY KEY, actor_id uuid NOT NULL REFERENCES user_account(id),
 action varchar(100) NOT NULL, resource_type varchar(100) NOT NULL, resource_id uuid NOT NULL,
 detail jsonb NOT NULL DEFAULT '{}', created_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE audit_event IS '追加审计；只存变更元数据，禁止密码、令牌与私聊正文';
CREATE TABLE idempotency_record (
 actor_id uuid NOT NULL REFERENCES user_account(id), method varchar(10) NOT NULL,
 path varchar(500) NOT NULL, key varchar(128) NOT NULL, request_hash varchar(64) NOT NULL,
 response_status integer, response_body text, response_etag varchar(40),
 created_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(actor_id,method,path,key)
);
COMMENT ON TABLE idempotency_record IS '与业务同一事务提交；唯一键串行化并发重放，不提前提交处理中记录';
CREATE TABLE outbox_event (
 id uuid PRIMARY KEY, aggregate_id uuid NOT NULL, event_type varchar(100) NOT NULL,
 payload jsonb NOT NULL, status varchar(20) NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','SENDING','SENT')),
 attempts integer NOT NULL DEFAULT 0, lease_until timestamptz, generation bigint NOT NULL DEFAULT 0,
 created_at timestamptz NOT NULL DEFAULT now(), sent_at timestamptz
);
CREATE INDEX outbox_pending_idx ON outbox_event(status,created_at) WHERE status <> 'SENT';
CREATE TABLE inbox_event(event_id uuid NOT NULL,consumer varchar(100) NOT NULL,created_at timestamptz NOT NULL DEFAULT now(),PRIMARY KEY(event_id,consumer));
