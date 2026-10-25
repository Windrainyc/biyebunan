-- 索引是可重建读模型；内容代次和任务租约代次分离，防止旧任务复活撤回内容。
CREATE TABLE knowledge_index_task (
 id uuid PRIMARY KEY,content_id uuid NOT NULL UNIQUE REFERENCES knowledge_content(id),snapshot_id uuid NOT NULL REFERENCES knowledge_version(id),
 content_generation bigint NOT NULL,state varchar(16) NOT NULL DEFAULT 'QUEUED' CHECK(state IN ('QUEUED','RUNNING','SUCCEEDED','FAILED','DEAD')),
 attempts integer NOT NULL DEFAULT 0,generation bigint NOT NULL DEFAULT 0,lease_until timestamptz,next_attempt_at timestamptz NOT NULL DEFAULT now(),
 error_code varchar(80),version bigint NOT NULL DEFAULT 1,created_at timestamptz NOT NULL DEFAULT now(),updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX knowledge_index_claim_idx ON knowledge_index_task(state,next_attempt_at,lease_until);
CREATE TABLE knowledge_chunk (
 id uuid PRIMARY KEY,content_id uuid NOT NULL REFERENCES knowledge_content(id),version_id uuid NOT NULL REFERENCES knowledge_version(id),
 generation bigint NOT NULL,ordinal integer NOT NULL,body varchar(2000) NOT NULL,UNIQUE(content_id,generation,ordinal)
);
CREATE INDEX knowledge_chunk_generation_idx ON knowledge_chunk(content_id,generation,version_id);
-- 当前检索提供可验证的关键词命中，不构造假向量；后续独立embedding任务补充pgvector读模型。
CREATE OR REPLACE VIEW operational_job AS
 SELECT t.asset_id AS id,t.version,t.created_at,t.updated_at,'ASSET_VALIDATION'::text AS kind,
 CASE WHEN t.state='DONE' THEN CASE WHEN a.state='READY' AND a.deleted_at IS NULL THEN 'SUCCEEDED' ELSE 'DEAD' END ELSE t.state END AS state,
 CASE WHEN t.state='DONE' THEN 100 ELSE 0 END::bigint AS progress,
 CASE WHEN t.state='DONE' AND a.state='READY' AND a.deleted_at IS NULL THEN a.id ELSE NULL END AS result_asset_id,t.error_code
 FROM asset_validation_task t JOIN asset a ON a.id=t.asset_id
 UNION ALL SELECT id,version,created_at,updated_at,'KNOWLEDGE_INDEX',state,CASE WHEN state='SUCCEEDED' THEN 100 ELSE 0 END::bigint,NULL::uuid,error_code FROM knowledge_index_task;
