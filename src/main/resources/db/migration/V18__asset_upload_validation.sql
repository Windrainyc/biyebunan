-- 资产生命周期：上传键仅用于隔离区，验证副本键独立；外部IO不占用数据库事务。
CREATE TABLE asset (
 id uuid PRIMARY KEY,upload_id uuid NOT NULL UNIQUE,owner_type varchar(16) NOT NULL CHECK(owner_type IN ('PROJECT','RECEIPT')),
 project_id uuid REFERENCES software_project(id) ON DELETE RESTRICT,receipt_order_id uuid REFERENCES software_order(id) ON DELETE RESTRICT,
 storage_id uuid NOT NULL REFERENCES storage_config(id) ON DELETE RESTRICT,
 filename varchar(200) NOT NULL,size bigint NOT NULL CHECK(size BETWEEN 1 AND 2147483648),sha256 char(64) NOT NULL CHECK(sha256 ~ '^[a-f0-9]{64}$'),mime_type varchar(200) NOT NULL,
 upload_key varchar(512) NOT NULL UNIQUE,private_key varchar(512),object_version text,
 state varchar(16) NOT NULL DEFAULT 'UPLOADING' CHECK(state IN ('UPLOADING','QUARANTINED','READY','REJECTED')),
 upload_expires_at timestamptz NOT NULL,deleted_at timestamptz,
 version bigint NOT NULL DEFAULT 1,created_by uuid NOT NULL REFERENCES user_account(id),updated_by uuid NOT NULL REFERENCES user_account(id),
 created_at timestamptz NOT NULL DEFAULT now(),updated_at timestamptz NOT NULL DEFAULT now(),
 CHECK((owner_type='PROJECT' AND project_id IS NOT NULL AND receipt_order_id IS NULL) OR (owner_type='RECEIPT' AND receipt_order_id IS NOT NULL AND project_id IS NULL)),
 CHECK(state!='READY' OR private_key IS NOT NULL)
);
CREATE INDEX asset_project_idx ON asset(project_id,state) WHERE deleted_at IS NULL;
CREATE INDEX asset_receipt_idx ON asset(receipt_order_id,state) WHERE deleted_at IS NULL;
CREATE TABLE asset_validation_task (
 asset_id uuid PRIMARY KEY REFERENCES asset(id) ON DELETE RESTRICT,
 state varchar(16) NOT NULL DEFAULT 'QUEUED' CHECK(state IN ('QUEUED','RUNNING','DONE','FAILED')),
 attempts integer NOT NULL DEFAULT 0,generation bigint NOT NULL DEFAULT 0,lease_until timestamptz,next_attempt_at timestamptz NOT NULL DEFAULT now(),error_code varchar(80),
 created_at timestamptz NOT NULL DEFAULT now(),updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX asset_task_claim_idx ON asset_validation_task(state,next_attempt_at,lease_until);
COMMENT ON TABLE asset_validation_task IS '持久任务队列，SKIP LOCKED领取、十分钟租约和generation防陈旧回写；扫描不可用保持隔离且重试';
