-- 部署配置目录；不在数据库持久化对象存储明文凭证和 TLS 私钥。
CREATE TABLE storage_config (
 id uuid PRIMARY KEY,name varchar(200) NOT NULL,provider varchar(32) NOT NULL CHECK(provider IN ('ALIYUN_OSS','S3_COMPATIBLE')),
 bucket varchar(63) NOT NULL,region varchar(200) NOT NULL,endpoint varchar(2048) NOT NULL,credential_ref varchar(200) NOT NULL,
 access varchar(16) NOT NULL CHECK(access='PRIVATE'),status varchar(16) NOT NULL CHECK(status IN ('ENABLED','DISABLED')),
 version bigint NOT NULL DEFAULT 1,created_by uuid NOT NULL REFERENCES user_account(id),updated_by uuid NOT NULL REFERENCES user_account(id),
 created_at timestamptz NOT NULL DEFAULT now(),updated_at timestamptz NOT NULL DEFAULT now(),UNIQUE(provider,endpoint,bucket)
);
CREATE TABLE domain_config (
 id uuid PRIMARY KEY,name varchar(200) NOT NULL,hostname varchar(253) NOT NULL UNIQUE CHECK(hostname=lower(hostname)),
 platform varchar(16) NOT NULL CHECK(platform IN ('admin','partner','student','assets')),certificate_ref varchar(200) NOT NULL,
 status varchar(16) NOT NULL CHECK(status IN ('ENABLED','DISABLED')),version bigint NOT NULL DEFAULT 1,
 created_by uuid NOT NULL REFERENCES user_account(id),updated_by uuid NOT NULL REFERENCES user_account(id),
 created_at timestamptz NOT NULL DEFAULT now(),updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX storage_config_page_idx ON storage_config(status,created_at DESC,id DESC);
CREATE INDEX domain_config_page_idx ON domain_config(status,created_at DESC,id DESC);
COMMENT ON TABLE storage_config IS '配置目录；credential_ref由部署层解析，启用状态不代表连通性或桶权限验证通过';
COMMENT ON TABLE domain_config IS '域名配置元数据；TLS与DNS由部署层管理，保存记录不自动修改网络';
