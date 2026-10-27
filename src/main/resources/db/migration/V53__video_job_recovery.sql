-- 视频任务具有独立ID和CAS版本，避免与同资产校验任务ID冲突。
ALTER TABLE video_task ADD COLUMN id uuid NOT NULL DEFAULT gen_random_uuid() UNIQUE;
ALTER TABLE video_task ADD COLUMN version bigint NOT NULL DEFAULT 1;
CREATE OR REPLACE VIEW operational_job AS
SELECT t.asset_id AS id,t.version,t.created_at,t.updated_at,'ASSET_VALIDATION'::text AS kind,
 CASE WHEN t.state='DONE' THEN CASE WHEN a.state='READY' AND a.deleted_at IS NULL THEN 'SUCCEEDED' ELSE 'DEAD' END ELSE t.state END AS state,
 CASE WHEN t.state='DONE' THEN 100 ELSE 0 END::bigint AS progress,
 CASE WHEN t.state='DONE' AND a.state='READY' AND a.deleted_at IS NULL THEN a.id ELSE NULL END AS result_asset_id,t.error_code
 FROM asset_validation_task t JOIN asset a ON a.id=t.asset_id
 UNION ALL SELECT id,version,created_at,updated_at,'KNOWLEDGE_INDEX',state,CASE WHEN state='SUCCEEDED' THEN 100 ELSE 0 END::bigint,NULL::uuid,error_code FROM knowledge_index_task
 UNION ALL SELECT id,version,created_at,updated_at,'BILLING_RECONCILIATION',state,CASE WHEN state='QUEUED' THEN 0 ELSE 100 END::bigint,NULL::uuid,error_code FROM billing_reconciliation_task
 UNION ALL SELECT id,version,created_at,updated_at,'EXPORT_'||kind,state,CASE WHEN state='SUCCEEDED' THEN 100 ELSE 0 END::bigint,NULL::uuid,error_code FROM export_job
 UNION ALL SELECT id,version,created_at,updated_at,'PROBE_'||kind,state,CASE WHEN state='SUCCEEDED' THEN 100 ELSE 0 END::bigint,NULL::uuid,error_code FROM configuration_probe_job
 UNION ALL SELECT id,version,created_at,updated_at,'VIDEO_TRANSCODE',CASE WHEN state='SKIPPED' THEN 'DEAD' ELSE state END,CASE WHEN state='SUCCEEDED' THEN 100 ELSE 0 END::bigint,CASE WHEN state='SUCCEEDED' THEN asset_id ELSE NULL END,error_code::varchar(80) FROM video_task;
