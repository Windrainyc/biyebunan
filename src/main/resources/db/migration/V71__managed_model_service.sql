CREATE TABLE managed_model_service (
 provider varchar(32) PRIMARY KEY CHECK(provider='deepseek'),
 version bigint NOT NULL DEFAULT 1, enabled boolean NOT NULL DEFAULT false,
 secret_ref uuid REFERENCES secret_material(id), checked_at timestamptz,
 check_error varchar(64), available_codes text NOT NULL DEFAULT '',
 updated_at timestamptz NOT NULL DEFAULT now()
);
INSERT INTO managed_model_service(provider) VALUES ('deepseek');
CREATE TABLE managed_model_binding (
 model_id uuid PRIMARY KEY REFERENCES model_definition(id),
 provider varchar(32) NOT NULL REFERENCES managed_model_service(provider),
 selected boolean NOT NULL DEFAULT false,
 price_version varchar(64) NOT NULL
);
COMMENT ON TABLE managed_model_service IS '官方供应商配置；默认关闭，密钥仅引用加密仓库，连接检查不生成文本';
COMMENT ON TABLE managed_model_binding IS '托管模型映射，历史模型与价格不覆盖';
