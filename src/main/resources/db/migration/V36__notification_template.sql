-- 模板目录仅管理模板元数据，不触发短信；发送快照应使用外键 RESTRICT 保留模板引用。
CREATE TABLE notification_template (
 id uuid PRIMARY KEY,
 name varchar(200) NOT NULL CHECK(length(trim(name))>0),
 code varchar(200) NOT NULL UNIQUE CHECK(length(trim(code))>0),
 channel varchar(16) NOT NULL CHECK(channel IN ('SMS','IN_APP')),
 provider_template_id varchar(200) NOT NULL CHECK(length(trim(provider_template_id))>0),
 status varchar(16) NOT NULL CHECK(status IN ('ENABLED','DISABLED')),
 version bigint NOT NULL DEFAULT 1 CHECK(version>0),
 created_by uuid NOT NULL REFERENCES user_account(id),
 updated_by uuid NOT NULL REFERENCES user_account(id),
 created_at timestamptz NOT NULL DEFAULT now(),
 updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX notification_template_status_created_idx ON notification_template(status,created_at,id);
COMMENT ON TABLE notification_template IS '通知模板配置目录；启用不代表供应商可用，未接通发送器时禁止伪造投递成功';
COMMENT ON COLUMN notification_template.provider_template_id IS 'SMS供应商模板编号；IN_APP为受控渲染器模板代码，不执行用户模板表达式';
