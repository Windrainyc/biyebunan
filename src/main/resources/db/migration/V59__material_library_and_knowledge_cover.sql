-- 独立素材分类；编码唯一，已被资产引用的分类禁止物理删除。
CREATE TABLE material_category (
 id uuid PRIMARY KEY, name varchar(100) NOT NULL, code varchar(64) NOT NULL UNIQUE,
 status varchar(16) NOT NULL CHECK(status IN ('ENABLED','DISABLED')), version bigint NOT NULL DEFAULT 1,
 created_by uuid NOT NULL REFERENCES user_account(id), updated_by uuid NOT NULL REFERENCES user_account(id),
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE material_category IS '管理端共享素材分类，停用后禁止新增上传，已有知识引用保留';
ALTER TABLE asset ADD COLUMN material_category_id uuid REFERENCES material_category(id) ON DELETE RESTRICT;
ALTER TABLE asset DROP CONSTRAINT asset_owner_type_check;
ALTER TABLE asset DROP CONSTRAINT asset_owner_consistency;
ALTER TABLE asset ADD CONSTRAINT asset_owner_type_check CHECK(owner_type IN ('PROJECT','RECEIPT','KNOWLEDGE','FORUM','MATERIAL'));
ALTER TABLE asset ADD CONSTRAINT asset_owner_consistency CHECK(
 (owner_type='PROJECT' AND project_id IS NOT NULL AND num_nonnulls(project_id,receipt_order_id,content_id,forum_post_id,material_category_id)=1) OR
 (owner_type='RECEIPT' AND receipt_order_id IS NOT NULL AND num_nonnulls(project_id,receipt_order_id,content_id,forum_post_id,material_category_id)=1) OR
 (owner_type='KNOWLEDGE' AND content_id IS NOT NULL AND num_nonnulls(project_id,receipt_order_id,content_id,forum_post_id,material_category_id)=1) OR
 (owner_type='FORUM' AND forum_post_id IS NOT NULL AND num_nonnulls(project_id,receipt_order_id,content_id,forum_post_id,material_category_id)=1) OR
 (owner_type='MATERIAL' AND material_category_id IS NOT NULL AND num_nonnulls(project_id,receipt_order_id,content_id,forum_post_id,material_category_id)=1));
CREATE INDEX asset_material_page_idx ON asset(material_category_id,created_at DESC,id) WHERE owner_type='MATERIAL' AND deleted_at IS NULL;
-- 封面与发布快照均保留资产外键，删除与关联写入由资产行锁串行化。
ALTER TABLE knowledge_content ADD COLUMN cover_asset_id uuid REFERENCES asset(id) ON DELETE RESTRICT;
ALTER TABLE knowledge_version ADD COLUMN cover_asset_id uuid REFERENCES asset(id) ON DELETE RESTRICT;
CREATE INDEX knowledge_cover_idx ON knowledge_content(cover_asset_id) WHERE cover_asset_id IS NOT NULL;

-- 独立素材权限，管理员角色按原有授权策略显式分配。
INSERT INTO permission VALUES ('materials:read','materials','查询素材库和分类'),('materials:write','materials','管理共享素材和分类');
INSERT INTO permission_platform VALUES ('materials:read','admin'),('materials:write','admin');

INSERT INTO role_permission(role_id,permission_code) SELECT id,p FROM role CROSS JOIN unnest(ARRAY['materials:read','materials:write']) p WHERE code='SUPER_ADMIN' AND built_in=true AND platform='admin' ON CONFLICT DO NOTHING;
