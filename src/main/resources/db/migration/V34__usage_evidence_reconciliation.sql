-- 保存原模型调用返回的可信迟到用量。取消/过期后不改对话终态，核对任务独立处理账务。
CREATE TABLE run_usage_evidence (
 run_id uuid PRIMARY KEY REFERENCES generation_run(id),
 kind varchar(16) NOT NULL CHECK(kind IN ('USAGE','NO_CHARGE')),
 input_tokens bigint,output_tokens bigint,cached_tokens bigint,
 provider_request_id varchar(500),
 lease_owner uuid NOT NULL,generation bigint NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),
 CHECK((kind='USAGE' AND input_tokens>=0 AND output_tokens>=0 AND cached_tokens>=0 AND cached_tokens<=input_tokens AND input_tokens IS NOT NULL AND output_tokens IS NOT NULL AND cached_tokens IS NOT NULL)
 OR(kind='NO_CHARGE' AND input_tokens IS NULL AND output_tokens IS NULL AND cached_tokens IS NULL))
);
CREATE TRIGGER run_usage_evidence_immutable BEFORE UPDATE OR DELETE ON run_usage_evidence FOR EACH ROW EXECUTE FUNCTION protect_quota_ledger();
CREATE TABLE billing_reconciliation_task (
 id uuid PRIMARY KEY,run_id uuid NOT NULL REFERENCES generation_run(id),
 state varchar(16) NOT NULL DEFAULT 'QUEUED' CHECK(state IN ('QUEUED','SUCCEEDED','FAILED')),
 reason varchar(1000) NOT NULL,created_by uuid NOT NULL REFERENCES user_account(id),
 error_code varchar(80),version bigint NOT NULL DEFAULT 1,
 created_at timestamptz NOT NULL DEFAULT now(),updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX billing_reconciliation_active_idx ON billing_reconciliation_task(run_id) WHERE state='QUEUED';
CREATE INDEX billing_reconciliation_poll_idx ON billing_reconciliation_task(state,created_at,id);
COMMENT ON TABLE run_usage_evidence IS '仅原执行器匹配owner/generation的返回可写；无公开写接口，不自动相信用户提交用量';
COMMENT ON TABLE billing_reconciliation_task IS '核对现有调用证据，不重发模型请求；无可信证据明确失败并保留冻结';
CREATE OR REPLACE VIEW operational_job AS
 SELECT t.asset_id AS id,t.version,t.created_at,t.updated_at,'ASSET_VALIDATION'::text AS kind,
 CASE WHEN t.state='DONE' THEN CASE WHEN a.state='READY' AND a.deleted_at IS NULL THEN 'SUCCEEDED' ELSE 'DEAD' END ELSE t.state END AS state,
 CASE WHEN t.state='DONE' THEN 100 ELSE 0 END::bigint AS progress,
 CASE WHEN t.state='DONE' AND a.state='READY' AND a.deleted_at IS NULL THEN a.id ELSE NULL END AS result_asset_id,t.error_code
 FROM asset_validation_task t JOIN asset a ON a.id=t.asset_id
 UNION ALL SELECT id,version,created_at,updated_at,'KNOWLEDGE_INDEX',state,CASE WHEN state='SUCCEEDED' THEN 100 ELSE 0 END::bigint,NULL::uuid,error_code FROM knowledge_index_task
 UNION ALL SELECT id,version,created_at,updated_at,'BILLING_RECONCILIATION',state,CASE WHEN state='QUEUED' THEN 0 ELSE 100 END::bigint,NULL::uuid,error_code FROM billing_reconciliation_task;
