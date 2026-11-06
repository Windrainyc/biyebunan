-- 凭证只保存带用途绑定的密文；更换密钥撤销检查状态，不改变历史对象位置。
CREATE TABLE storage_secret (
 storage_id uuid PRIMARY KEY REFERENCES storage_config(id),
 version bigint NOT NULL DEFAULT 1, access_cipher text NOT NULL, secret_cipher text NOT NULL,
 access_tail varchar(8) NOT NULL, checked_at timestamptz, check_error varchar(80),
 updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE storage_default (
 singleton boolean PRIMARY KEY DEFAULT true CHECK(singleton),
 storage_id uuid REFERENCES storage_config(id), version bigint NOT NULL DEFAULT 1
);
INSERT INTO storage_default(singleton) VALUES(true);
COMMENT ON TABLE storage_secret IS 'OSS凭证AES-GCM密文，主密钥不入库；不向接口返回密文';

CREATE TABLE storage_migration (
 asset_id uuid PRIMARY KEY REFERENCES asset(id), source_id uuid NOT NULL REFERENCES storage_config(id), target_id uuid NOT NULL REFERENCES storage_config(id),
 source_key varchar(512) NOT NULL,source_version text,source_asset_version bigint NOT NULL,
 target_key varchar(512),target_version text, state varchar(16) NOT NULL DEFAULT 'QUEUED' CHECK(state IN ('QUEUED','RUNNING','DONE','FAILED')),
 generation bigint NOT NULL DEFAULT 0,lease_until timestamptz,error_code varchar(80),
 created_at timestamptz NOT NULL DEFAULT now(),updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE storage_migration IS '逐资产迁移清单：大小与SHA256一致后CAS换位置，源对象保留；租约和代次拒绝陈旧提交';

CREATE TABLE storage_migration_attempt(asset_id uuid NOT NULL REFERENCES storage_migration(asset_id),generation bigint NOT NULL,target_id uuid NOT NULL REFERENCES storage_config(id),object_key varchar(512) NOT NULL UNIQUE,created_at timestamptz NOT NULL DEFAULT now(),PRIMARY KEY(asset_id,generation));
