-- 私聊会话归属固定；删除仅隐藏，不删除未来账务引用。
CREATE TABLE chat_conversation (
 id uuid PRIMARY KEY,project_id uuid NOT NULL REFERENCES software_project(id),
 student_id uuid NOT NULL REFERENCES user_account(id),title varchar(200) NOT NULL,
 deleted_at timestamptz,version bigint NOT NULL DEFAULT 1,
 created_at timestamptz NOT NULL DEFAULT now(),updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX conversation_student_page_idx ON chat_conversation(student_id,created_at DESC,id DESC) WHERE deleted_at IS NULL;
CREATE INDEX conversation_project_idx ON chat_conversation(project_id,student_id);
COMMENT ON TABLE chat_conversation IS '每次读写同时检查固定学生归属与当前订单归属，客户端不能切换身份；中介无访问入口';
