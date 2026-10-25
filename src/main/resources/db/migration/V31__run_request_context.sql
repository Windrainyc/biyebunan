-- 固定调用上下文不包含密钥。凭证仅保存受控ID，调用前读取当前密文以支持轮换。
CREATE TABLE run_context (
 run_id uuid PRIMARY KEY REFERENCES generation_run(id),key_id uuid NOT NULL REFERENCES model_key(id),
 model_code varchar(200) NOT NULL,endpoint varchar(2048) NOT NULL,max_output_tokens integer NOT NULL CHECK(max_output_tokens BETWEEN 1 AND 8192),
 messages jsonb NOT NULL CHECK(jsonb_typeof(messages)='array'),created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE run_source (
 run_id uuid NOT NULL REFERENCES generation_run(id),version_id uuid NOT NULL REFERENCES knowledge_version(id),
 chunk_id uuid NOT NULL,quote varchar(2000) NOT NULL,page integer NOT NULL CHECK(page>0),PRIMARY KEY(run_id,chunk_id)
);
CREATE INDEX run_source_version_idx ON run_source(version_id,run_id);
CREATE FUNCTION protect_run_snapshot() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'run snapshot is immutable' USING ERRCODE='23514'; END; $$;
CREATE TRIGGER run_context_immutable BEFORE UPDATE OR DELETE ON run_context FOR EACH ROW EXECUTE FUNCTION protect_run_snapshot();
CREATE TRIGGER run_source_immutable BEFORE UPDATE OR DELETE ON run_source FOR EACH ROW EXECUTE FUNCTION protect_run_snapshot();
COMMENT ON COLUMN run_source.chunk_id IS '快照记录原chunk UUID，不加可重建索引外键，避免重建删除历史引用';
