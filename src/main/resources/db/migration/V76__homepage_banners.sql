CREATE TABLE homepage_banner (
 id uuid PRIMARY KEY, title varchar(80) NOT NULL, subtitle varchar(160),
 cover_asset_id uuid REFERENCES asset(id) ON DELETE RESTRICT,
 poster_asset_id uuid REFERENCES asset(id) ON DELETE RESTRICT,
 body text, sort_order integer NOT NULL DEFAULT 0 CHECK(sort_order BETWEEN 0 AND 9999),
 status varchar(16) NOT NULL CHECK(status IN ('ENABLED','DISABLED')), version bigint NOT NULL DEFAULT 1,
 created_by uuid NOT NULL REFERENCES user_account(id),updated_by uuid NOT NULL REFERENCES user_account(id),
 created_at timestamptz NOT NULL DEFAULT now(),updated_at timestamptz NOT NULL DEFAULT now(),
 CHECK(poster_asset_id IS NOT NULL OR COALESCE(length(trim(body)),0)>0)
);
CREATE INDEX homepage_banner_active_idx ON homepage_banner(sort_order,id) WHERE status='ENABLED';
INSERT INTO permission VALUES ('banners:read','banners','查看首页横幅'),('banners:write','banners','管理首页横幅'),('get_student_banners','banners','读取已启用首页横幅');
INSERT INTO permission_platform VALUES ('banners:read','admin'),('banners:write','admin'),('get_student_banners','student');
INSERT INTO role_permission(role_id,permission_code) SELECT id,p FROM role CROSS JOIN unnest(ARRAY['banners:read','banners:write']) p WHERE platform='admin' AND built_in=true AND code='SUPER_ADMIN' ON CONFLICT DO NOTHING;
INSERT INTO role_permission(role_id,permission_code) SELECT id,'get_student_banners' FROM role WHERE platform='student' ON CONFLICT DO NOTHING;

INSERT INTO platform_menu(id,name,code,platform,path,permission_code,sort_order,status,created_by,updated_by)
SELECT gen_random_uuid(),'首页横幅','homepage-banners','admin','/admin/banners','banners:read',185,'ENABLED',u.id,u.id
FROM (SELECT id FROM user_account WHERE platform='admin' AND status='ENABLED' ORDER BY created_at,id LIMIT 1) u
WHERE EXISTS(SELECT 1 FROM platform_menu WHERE platform='admin') ON CONFLICT DO NOTHING;
