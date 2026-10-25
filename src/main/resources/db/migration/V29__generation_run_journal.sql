-- 运行持久化独立于HTTP连接；client_message_id在学生范围永久唯一。
ALTER TABLE chat_conversation ADD CONSTRAINT conversation_student_pair UNIQUE(id,student_id);
ALTER TABLE model_price ADD CONSTRAINT model_price_model_pair UNIQUE(id,model_id);
CREATE TABLE generation_run (
 id uuid PRIMARY KEY,student_id uuid NOT NULL REFERENCES user_account(id),conversation_id uuid NOT NULL REFERENCES chat_conversation(id),
 client_message_id uuid NOT NULL,request_hash varchar(64) NOT NULL,model_id uuid NOT NULL REFERENCES model_definition(id),price_id uuid NOT NULL REFERENCES model_price(id),
 state varchar(16) NOT NULL DEFAULT 'RESERVED' CHECK(state IN ('RESERVED','RUNNING','SUCCEEDED','FAILED','CANCELLED')),
 version bigint NOT NULL DEFAULT 1,last_sequence bigint NOT NULL DEFAULT 0 CHECK(last_sequence>=0),
 call_started_at timestamptz,lease_owner uuid,lease_until timestamptz,generation bigint NOT NULL DEFAULT 0,
 error_code varchar(80),provider_request_id varchar(200),created_at timestamptz NOT NULL DEFAULT now(),updated_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY(conversation_id,student_id) REFERENCES chat_conversation(id,student_id),
 FOREIGN KEY(price_id,model_id) REFERENCES model_price(id,model_id),
 UNIQUE(student_id,client_message_id),CHECK(version>0 AND generation>=0),CHECK((lease_owner IS NULL)=(lease_until IS NULL))
);
CREATE INDEX generation_run_conversation_idx ON generation_run(conversation_id,created_at,id);
CREATE INDEX generation_run_work_idx ON generation_run(state,lease_until,created_at) WHERE state IN ('RESERVED','RUNNING');
CREATE TABLE run_event (
 run_id uuid NOT NULL REFERENCES generation_run(id),sequence bigint NOT NULL CHECK(sequence>0),
 type varchar(32) NOT NULL CHECK(type IN ('accepted','delta','citation','usage','completed','failed','cancelled')),
 payload jsonb NOT NULL CHECK(jsonb_typeof(payload)='object'),created_at timestamptz NOT NULL DEFAULT now(),
 expires_at timestamptz NOT NULL DEFAULT now()+interval '7 days',CHECK(expires_at>created_at),PRIMARY KEY(run_id,sequence)
);
CREATE INDEX run_event_expiry_idx ON run_event(expires_at);
-- 事件必须不可覆盖；清理过期事件允许DELETE，回放由运行最高序号和保留窗口检测缺口。
CREATE FUNCTION protect_run_event_update() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'run event is immutable' USING ERRCODE='23514'; END; $$;
CREATE TRIGGER run_event_immutable BEFORE UPDATE ON run_event FOR EACH ROW EXECUTE FUNCTION protect_run_event_update();
COMMENT ON TABLE generation_run IS '已开始的外部调用租约到期只能核对，不能按普通任务重试供应商';
COMMENT ON TABLE run_event IS '运行内序号单调，序号分配与事件插入同事务；回放绝不触发调用或计费';

CREATE UNIQUE INDEX run_event_terminal_once ON run_event(run_id) WHERE type IN ('completed','failed','cancelled');
CREATE UNIQUE INDEX run_event_accepted_once ON run_event(run_id) WHERE type='accepted';
