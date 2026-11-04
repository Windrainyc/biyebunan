-- 仅对已配置导航的管理端补充新入口；不改变未配置平台的自动导航模式，不覆盖既有菜单。
INSERT INTO platform_menu(id,name,code,platform,path,permission_code,sort_order,status,created_by,updated_by)
SELECT gen_random_uuid(),v.name,v.code,'admin',v.path,v.permission, v.sort,'ENABLED',u.id,u.id
FROM (VALUES ('服务套餐','service-packages','/admin/service-packages','packages:read',180),('用户活动','user-activity','/admin/activity','activity:read',190)) v(name,code,path,permission,sort)
CROSS JOIN LATERAL (SELECT id FROM user_account WHERE platform='admin' AND status='ENABLED' ORDER BY created_at,id LIMIT 1) u
WHERE EXISTS(SELECT 1 FROM platform_menu WHERE platform='admin')
ON CONFLICT DO NOTHING;
