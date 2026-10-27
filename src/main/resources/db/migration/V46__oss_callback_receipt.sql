-- OSS回调必须验签并HEAD验真；同上传会话通知和隔离任务原子落库。
CREATE TABLE oss_callback_receipt (
 upload_id uuid PRIMARY KEY,
 asset_id uuid NOT NULL UNIQUE REFERENCES asset(id),
 body_digest varchar(64) NOT NULL CHECK(body_digest ~ '^[0-9a-f]{64}$'),
 object_version varchar(1024),
 created_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE oss_callback_receipt IS '只存原文摘要和对象版本，不保存回调签名或授权链接；唯一键防重复副作用';
