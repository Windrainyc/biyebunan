CREATE TABLE service_package (
 id uuid PRIMARY KEY, name varchar(100) NOT NULL, code varchar(200) NOT NULL UNIQUE,
 status varchar(16) NOT NULL CHECK(status IN ('ENABLED','DISABLED')),
 version bigint NOT NULL DEFAULT 1, created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
 created_by uuid REFERENCES user_account(id), updated_by uuid REFERENCES user_account(id)
);
-- 历史订单套餐只补齐实际已有代码，不覆盖用户订单。
INSERT INTO service_package(id,name,code,status)
SELECT gen_random_uuid(),left(package_code,100),package_code,'ENABLED' FROM software_order GROUP BY package_code;
ALTER TABLE software_order ADD CONSTRAINT software_order_package_fk FOREIGN KEY(package_code) REFERENCES service_package(code) ON UPDATE RESTRICT ON DELETE RESTRICT;
INSERT INTO permission VALUES ('packages:read','orders','查询服务套餐'),('packages:write','orders','维护服务套餐'),('delete_admin_orders_id','orders','删除无业务引用订单');
INSERT INTO permission_platform VALUES ('packages:read','admin'),('packages:write','admin'),('delete_admin_orders_id','admin');
INSERT INTO role_permission(role_id,permission_code) SELECT id,p FROM role CROSS JOIN unnest(ARRAY['packages:read','packages:write','delete_admin_orders_id']) p WHERE code='SUPER_ADMIN' AND built_in=true AND platform='admin' ON CONFLICT DO NOTHING;
