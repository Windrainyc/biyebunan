-- 模型目录：元数据与凭证分离，后续价格、密钥和运行记录必须用外键引用。
CREATE TABLE model_definition (
 id uuid PRIMARY KEY,name varchar(200) NOT NULL,code varchar(200) NOT NULL UNIQUE,
 provider varchar(200) NOT NULL,endpoint varchar(2048) NOT NULL,
 context_limit integer NOT NULL CHECK(context_limit>0),
 max_output_tokens integer NOT NULL CHECK(max_output_tokens>0 AND max_output_tokens<=context_limit),
 status varchar(16) NOT NULL CHECK(status IN ('ENABLED','DISABLED')),version bigint NOT NULL DEFAULT 1,
 created_by uuid NOT NULL REFERENCES user_account(id),updated_by uuid NOT NULL REFERENCES user_account(id),
 created_at timestamptz NOT NULL DEFAULT now(),updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX model_status_page_idx ON model_definition(status,created_at DESC,id DESC);
COMMENT ON TABLE model_definition IS '供应商配置目录，启用不代表供应商连通性验证成功；不保存密钥';
