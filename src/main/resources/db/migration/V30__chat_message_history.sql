-- 消息正文以明确字符上限约束的text保存，避免8192输出词元被varchar(20000)截断。
ALTER TABLE generation_run ADD CONSTRAINT generation_run_conversation_pair UNIQUE(id,conversation_id);
CREATE TABLE chat_message (
 id uuid PRIMARY KEY,conversation_id uuid NOT NULL REFERENCES chat_conversation(id),run_id uuid NOT NULL,
 role varchar(16) NOT NULL CHECK(role IN ('USER','ASSISTANT')),text text NOT NULL,
 citations jsonb NOT NULL DEFAULT '[]'::jsonb CHECK(jsonb_typeof(citations)='array'),created_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY(run_id,conversation_id) REFERENCES generation_run(id,conversation_id),UNIQUE(run_id,role),
 CHECK(length(text)>0 AND length(text)<=CASE WHEN role='USER' THEN 16000 ELSE 262144 END)
);
CREATE INDEX chat_message_history_idx ON chat_message(conversation_id,created_at,id);
CREATE FUNCTION protect_chat_message_update() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'chat message is immutable' USING ERRCODE='23514'; END; $$;
CREATE TRIGGER chat_message_immutable BEFORE UPDATE OR DELETE ON chat_message FOR EACH ROW EXECUTE FUNCTION protect_chat_message_update();
COMMENT ON TABLE chat_message IS '同运行每个角色至多一条，正文不可覆盖；会话隐藏不删除账务与历史';
