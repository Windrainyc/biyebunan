-- 配置验证只执行受部署白名单约束的只读探测，不进行付费推理。
CREATE TABLE configuration_probe_job (
 id uuid PRIMARY KEY,kind varchar(16) NOT NULL CHECK(kind IN ('MODEL','STORAGE','DOMAIN')),
 target_id uuid NOT NULL,target_version bigint NOT NULL,created_by uuid NOT NULL REFERENCES user_account(id),reason varchar(1000) NOT NULL,
 state varchar(16) NOT NULL DEFAULT 'QUEUED' CHECK(state IN ('QUEUED','RUNNING','SUCCEEDED','FAILED')),
 generation bigint NOT NULL DEFAULT 0,attempts integer NOT NULL DEFAULT 0,lease_until timestamptz,error_code varchar(80),
 version bigint NOT NULL DEFAULT 1,created_at timestamptz NOT NULL DEFAULT now(),updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX configuration_probe_poll_idx ON configuration_probe_job(state,lease_until,created_at,id);
CREATE INDEX configuration_probe_owner_idx ON configuration_probe_job(created_by,created_at DESC);
COMMENT ON TABLE configuration_probe_job IS '探测结果绑定配置版本；配置删除或更新后不能将旧网络结果显示成当前配置验证成功';
CREATE OR REPLACE VIEW operational_job AS
 SELECT t.asset_id AS id,t.version,t.created_at,t.updated_at,'ASSET_VALIDATION'::text AS kind,
 CASE WHEN t.state='DONE' THEN CASE WHEN a.state='READY' AND a.deleted_at IS NULL THEN 'SUCCEEDED' ELSE 'DEAD' END ELSE t.state END AS state,
 CASE WHEN t.state='DONE' THEN 100 ELSE 0 END::bigint AS progress,
 CASE WHEN t.state='DONE' AND a.state='READY' AND a.deleted_at IS NULL THEN a.id ELSE NULL END AS result_asset_id,t.error_code
 FROM asset_validation_task t JOIN asset a ON a.id=t.asset_id
 UNION ALL SELECT id,version,created_at,updated_at,'KNOWLEDGE_INDEX',state,CASE WHEN state='SUCCEEDED' THEN 100 ELSE 0 END::bigint,NULL::uuid,error_code FROM knowledge_index_task
 UNION ALL SELECT id,version,created_at,updated_at,'BILLING_RECONCILIATION',state,CASE WHEN state='QUEUED' THEN 0 ELSE 100 END::bigint,NULL::uuid,error_code FROM billing_reconciliation_task
 UNION ALL SELECT id,version,created_at,updated_at,'EXPORT_'||kind,state,CASE WHEN state='SUCCEEDED' THEN 100 ELSE 0 END::bigint,NULL::uuid,error_code FROM export_job
 UNION ALL SELECT id,version,created_at,updated_at,'PROBE_'||kind,state,CASE WHEN state='SUCCEEDED' THEN 100 ELSE 0 END::bigint,NULL::uuid,error_code FROM configuration_probe_job;
