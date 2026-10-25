-- 运维任务使用独立CAS版本；generation仅负责worker租约隔离。
ALTER TABLE asset_validation_task ADD COLUMN version bigint NOT NULL DEFAULT 1;
CREATE TABLE job_retry_event (
 id uuid PRIMARY KEY,job_id uuid NOT NULL REFERENCES asset_validation_task(asset_id),
 actor_id uuid NOT NULL REFERENCES user_account(id),reason varchar(1000) NOT NULL,
 previous_error varchar(80),created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX job_retry_event_job_idx ON job_retry_event(job_id,created_at,id);
-- 展示任务状态与实际结果一致，恶意文件检测完成不等于校验成功。
CREATE VIEW operational_job AS SELECT t.asset_id AS id,t.version,t.created_at,t.updated_at,
 'ASSET_VALIDATION'::text AS kind,
 CASE WHEN t.state='DONE' THEN CASE WHEN a.state='READY' AND a.deleted_at IS NULL THEN 'SUCCEEDED' ELSE 'DEAD' END ELSE t.state END AS state,
 CASE WHEN t.state='DONE' THEN 100 ELSE 0 END::bigint AS progress,
 CASE WHEN t.state='DONE' AND a.state='READY' AND a.deleted_at IS NULL THEN a.id ELSE NULL END AS result_asset_id,t.error_code
 FROM asset_validation_task t JOIN asset a ON a.id=t.asset_id;
COMMENT ON VIEW operational_job IS '当前仅真实资产校验任务；后续按各类持久队列扩展，不返回伪造的转码或导出任务';
