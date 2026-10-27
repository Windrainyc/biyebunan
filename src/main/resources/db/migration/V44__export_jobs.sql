-- 导出是独立私有报告，不复用用户上传资产；每次尝试独立对象键便于失败回收。
CREATE TABLE export_job (
 id uuid PRIMARY KEY,created_by uuid NOT NULL REFERENCES user_account(id),platform varchar(16) NOT NULL CHECK(platform IN ('admin','partner')),
 kind varchar(16) NOT NULL CHECK(kind IN ('ORDERS','PROJECTS','USAGE','AUDIT')),
 from_date date NOT NULL,to_date date NOT NULL CHECK(to_date>=from_date),storage_id uuid NOT NULL REFERENCES storage_config(id),
 state varchar(16) NOT NULL DEFAULT 'QUEUED' CHECK(state IN ('QUEUED','RUNNING','SUCCEEDED','FAILED')),
 attempts integer NOT NULL DEFAULT 0,generation bigint NOT NULL DEFAULT 0,lease_until timestamptz,
 includes_chat boolean NOT NULL DEFAULT false,object_key varchar(512),object_version text,result_expires_at timestamptz,error_code varchar(80),
 version bigint NOT NULL DEFAULT 1,created_at timestamptz NOT NULL DEFAULT now(),updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX export_job_poll_idx ON export_job(state,lease_until,created_at,id);
CREATE INDEX export_job_owner_idx ON export_job(created_by,created_at DESC,id DESC);
CREATE TABLE export_order_scope(job_id uuid NOT NULL REFERENCES export_job(id),order_id uuid NOT NULL REFERENCES software_order(id),PRIMARY KEY(job_id,order_id));
CREATE TABLE export_attempt (
 job_id uuid NOT NULL REFERENCES export_job(id),generation bigint NOT NULL,object_key varchar(512) NOT NULL UNIQUE,
 object_version text,created_at timestamptz NOT NULL DEFAULT now(),cleanup_until timestamptz,cleaned_at timestamptz,PRIMARY KEY(job_id,generation)
);
COMMENT ON TABLE export_order_scope IS '导出中包含的订单集合；中介下载时全部重新验证当前归属，变化则拒绝整个报告';
COMMENT ON TABLE export_attempt IS '每次尝试的唯一对象键；失效尝试延迟回收，避免租约失效后上传产生无人追踪对象';
CREATE OR REPLACE VIEW operational_job AS
 SELECT t.asset_id AS id,t.version,t.created_at,t.updated_at,'ASSET_VALIDATION'::text AS kind,
 CASE WHEN t.state='DONE' THEN CASE WHEN a.state='READY' AND a.deleted_at IS NULL THEN 'SUCCEEDED' ELSE 'DEAD' END ELSE t.state END AS state,
 CASE WHEN t.state='DONE' THEN 100 ELSE 0 END::bigint AS progress,
 CASE WHEN t.state='DONE' AND a.state='READY' AND a.deleted_at IS NULL THEN a.id ELSE NULL END AS result_asset_id,t.error_code
 FROM asset_validation_task t JOIN asset a ON a.id=t.asset_id
 UNION ALL SELECT id,version,created_at,updated_at,'KNOWLEDGE_INDEX',state,CASE WHEN state='SUCCEEDED' THEN 100 ELSE 0 END::bigint,NULL::uuid,error_code FROM knowledge_index_task
 UNION ALL SELECT id,version,created_at,updated_at,'BILLING_RECONCILIATION',state,CASE WHEN state='QUEUED' THEN 0 ELSE 100 END::bigint,NULL::uuid,error_code FROM billing_reconciliation_task
 UNION ALL SELECT id,version,created_at,updated_at,'EXPORT_'||kind,state,CASE WHEN state='SUCCEEDED' THEN 100 ELSE 0 END::bigint,NULL::uuid,error_code FROM export_job;
