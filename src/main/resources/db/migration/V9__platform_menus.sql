-- 菜单只是导航可见性，不能替代业务接口授权。树变更使用单独互斥行防止并发环。
CREATE TABLE menu_tree_mutex(id integer PRIMARY KEY CHECK(id=1));
INSERT INTO menu_tree_mutex VALUES(1);
CREATE TABLE platform_menu (
 id uuid PRIMARY KEY,name varchar(200) NOT NULL,code varchar(200) NOT NULL,
 platform varchar(16) NOT NULL CHECK(platform IN ('admin','student','partner')),
 parent_id uuid REFERENCES platform_menu(id) ON DELETE RESTRICT,
 path varchar(500) NOT NULL,permission_code varchar(200) NOT NULL,
 sort_order integer NOT NULL CHECK(sort_order>=0),status varchar(16) NOT NULL CHECK(status IN ('ENABLED','DISABLED')),
 version bigint NOT NULL DEFAULT 1,created_by uuid NOT NULL REFERENCES user_account(id),updated_by uuid NOT NULL REFERENCES user_account(id),
 created_at timestamptz NOT NULL DEFAULT now(),updated_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(platform,code),UNIQUE(platform,path),CHECK(parent_id IS NULL OR parent_id<>id),
 FOREIGN KEY(permission_code,platform) REFERENCES permission_platform(code,platform)
);
CREATE INDEX menu_parent_order_idx ON platform_menu(platform,parent_id,sort_order,id);
COMMENT ON TABLE platform_menu IS '当前平台、启用且有权限的完整祖先链才可见；删除有子项的菜单由外键拒绝';
