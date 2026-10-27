-- 无供应商可查询能力时的人工裁决，与原调用证据严格分表；双人复核不等于供应商自动认证。
INSERT INTO permission VALUES('billing:adjudicate','adjudications','双人复核未知用量证据');
INSERT INTO permission_platform VALUES('billing:adjudicate','admin');
INSERT INTO role_permission VALUES('00000000-0000-4000-8000-000000000001','billing:adjudicate');
CREATE TABLE usage_adjudication (
 id uuid PRIMARY KEY,run_id uuid NOT NULL REFERENCES generation_run(id),
 kind varchar(16) NOT NULL CHECK(kind IN ('USAGE','NO_CHARGE')),
 input_tokens bigint,output_tokens bigint,cached_tokens bigint,
 evidence_ref varchar(1000) NOT NULL,evidence_sha256 varchar(64) NOT NULL CHECK(evidence_sha256 ~ '^[0-9a-f]{64}$'),
 reason varchar(1000) NOT NULL,created_by uuid NOT NULL REFERENCES user_account(id),created_at timestamptz NOT NULL DEFAULT now(),
 state varchar(16) NOT NULL DEFAULT 'PENDING' CHECK(state IN ('PENDING','APPROVED','REJECTED')),
 version bigint NOT NULL DEFAULT 1,reviewed_by uuid REFERENCES user_account(id),review_reason varchar(1000),reviewed_at timestamptz,
 CHECK((kind='USAGE' AND input_tokens IS NOT NULL AND output_tokens IS NOT NULL AND cached_tokens IS NOT NULL AND input_tokens BETWEEN 0 AND 1000000000 AND output_tokens BETWEEN 0 AND 1000000000 AND cached_tokens BETWEEN 0 AND input_tokens)
 OR(kind='NO_CHARGE' AND input_tokens IS NULL AND output_tokens IS NULL AND cached_tokens IS NULL)),
 CHECK((state='PENDING' AND reviewed_by IS NULL AND reviewed_at IS NULL AND review_reason IS NULL) OR (state<>'PENDING' AND reviewed_by IS NOT NULL AND reviewed_by<>created_by AND reviewed_at IS NOT NULL AND review_reason IS NOT NULL))
);
CREATE UNIQUE INDEX adjudication_one_pending ON usage_adjudication(run_id) WHERE state='PENDING';
CREATE INDEX adjudication_list_idx ON usage_adjudication(created_at DESC,id);
CREATE FUNCTION protect_adjudication() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'adjudication cannot be deleted' USING ERRCODE='23514'; END IF;
 IF OLD.state<>'PENDING' OR NEW.state='PENDING' OR NEW.version<>OLD.version+1 OR
 (to_jsonb(NEW)-ARRAY['state','version','reviewed_by','review_reason','reviewed_at'])<>(to_jsonb(OLD)-ARRAY['state','version','reviewed_by','review_reason','reviewed_at']) THEN
 RAISE EXCEPTION 'adjudication evidence is immutable' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER adjudication_immutable BEFORE UPDATE OR DELETE ON usage_adjudication FOR EACH ROW EXECUTE FUNCTION protect_adjudication();
