-- 草稿只由作者读取，不批量改变历史帖状态。
ALTER TABLE forum_post DROP CONSTRAINT forum_post_state_check;
ALTER TABLE forum_post ADD CONSTRAINT forum_post_state_check CHECK(state IN ('DRAFT','PENDING','PUBLISHED','HIDDEN','LOCKED'));
INSERT INTO permission VALUES ('get_admin_me','profile','读取管理员本人资料'),('put_admin_me','profile','更新管理员本人资料');
INSERT INTO permission_platform VALUES ('get_admin_me','admin'),('put_admin_me','admin');
INSERT INTO role_permission(role_id,permission_code) SELECT id,p FROM role CROSS JOIN unnest(ARRAY['get_admin_me','put_admin_me']) p WHERE platform='admin' ON CONFLICT DO NOTHING;
