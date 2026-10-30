-- 管理员发布不可变公告/提醒；接收者快照与每人独立事件支持有界队列消费。
CREATE TABLE announcement (
 id uuid PRIMARY KEY, title varchar(200) NOT NULL, body text NOT NULL CHECK(length(body) BETWEEN 1 AND 10000),
 kind varchar(16) NOT NULL CHECK(kind IN ('ANNOUNCEMENT','REMINDER')),
 audience varchar(16) NOT NULL CHECK(audience IN ('ALL','SELECTED')),
 created_by uuid NOT NULL REFERENCES user_account(id), created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE announcement_recipient (
 announcement_id uuid NOT NULL REFERENCES announcement(id), recipient_id uuid NOT NULL REFERENCES user_account(id),
 event_id uuid NOT NULL UNIQUE, PRIMARY KEY(announcement_id,recipient_id)
);
COMMENT ON TABLE announcement IS '管理员不可变发布记录，正文仅按接收者范围可见';
COMMENT ON TABLE announcement_recipient IS '发布时固定的接收者和独立outbox事件，后注册用户不自动加入';
CREATE INDEX announcement_page_idx ON announcement(created_at DESC,id);
CREATE INDEX announcement_recipient_user_idx ON announcement_recipient(recipient_id,announcement_id);
INSERT INTO permission VALUES ('announcements:read','announcements','查询公告发送记录'),('announcements:write','announcements','向全部或指定用户发布公告提醒');
INSERT INTO permission_platform VALUES ('announcements:read','admin'),('announcements:write','admin');
INSERT INTO role_permission(role_id,permission_code) SELECT id,p FROM role CROSS JOIN unnest(ARRAY['announcements:read','announcements:write']) p WHERE code='SUPER_ADMIN' AND built_in=true AND platform='admin' ON CONFLICT DO NOTHING;
