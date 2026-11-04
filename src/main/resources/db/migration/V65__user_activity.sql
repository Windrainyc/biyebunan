CREATE TABLE user_activity (
 id uuid PRIMARY KEY,actor_id uuid NOT NULL REFERENCES user_account(id),platform varchar(16) NOT NULL,
 kind varchar(8) NOT NULL CHECK(kind IN ('LOGIN','VIEW')),path varchar(160) NOT NULL,
 content_id uuid REFERENCES knowledge_content(id),seconds integer NOT NULL DEFAULT 0 CHECK(seconds BETWEEN 0 AND 30),
 ip varchar(64) NOT NULL,created_at timestamptz NOT NULL DEFAULT now(),time_bucket bigint NOT NULL
);
CREATE INDEX user_activity_page_idx ON user_activity(created_at DESC,id DESC);
CREATE INDEX user_activity_actor_idx ON user_activity(actor_id,created_at DESC);
CREATE UNIQUE INDEX user_activity_active_bucket ON user_activity(actor_id,time_bucket) WHERE seconds>0;
COMMENT ON TABLE user_activity IS '上线后真实登录和浏览片段；时长为可见活跃估算，不作考勤或计费依据；不存正文与查询参数';
INSERT INTO permission VALUES ('activity:read','operations','查看用户访问与学习记录');
INSERT INTO permission_platform VALUES ('activity:read','admin');
INSERT INTO role_permission(role_id,permission_code) SELECT id,'activity:read' FROM role WHERE code='SUPER_ADMIN' AND built_in=true AND platform='admin' ON CONFLICT DO NOTHING;
