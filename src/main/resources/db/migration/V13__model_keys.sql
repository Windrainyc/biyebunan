-- 凭证元数据与密文分离；轮换删除旧引用，响应不含 secret_ref。
CREATE TABLE model_key (
 id uuid PRIMARY KEY,name varchar(200) NOT NULL,model_id uuid NOT NULL REFERENCES model_definition(id) ON DELETE RESTRICT,
 secret_ref uuid NOT NULL UNIQUE REFERENCES secret_material(id) ON DELETE RESTRICT,
 masked_suffix varchar(16) NOT NULL,rotated_at timestamptz NOT NULL DEFAULT now(),
 status varchar(16) NOT NULL CHECK(status IN ('ENABLED','DISABLED')),version bigint NOT NULL DEFAULT 1,
 created_by uuid NOT NULL REFERENCES user_account(id),updated_by uuid NOT NULL REFERENCES user_account(id),
 created_at timestamptz NOT NULL DEFAULT now(),updated_at timestamptz NOT NULL DEFAULT now(),UNIQUE(model_id,name)
);
CREATE INDEX model_key_selection_idx ON model_key(model_id,status,id);
COMMENT ON TABLE model_key IS '创建默认停用；仅适配器内部可通过secret_ref获取密钥，无对外明文读取接口';
