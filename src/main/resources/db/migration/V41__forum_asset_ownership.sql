-- 论坛附件必须属于已存在帖子；帖子内容只引用READY私有资产，草稿上传不能直接公开。
ALTER TABLE asset ADD COLUMN forum_post_id uuid REFERENCES forum_post(id) ON DELETE RESTRICT;
ALTER TABLE asset DROP CONSTRAINT asset_owner_type_check;
ALTER TABLE asset DROP CONSTRAINT asset_owner_consistency;
ALTER TABLE asset ADD CONSTRAINT asset_owner_type_check CHECK(owner_type IN ('PROJECT','RECEIPT','KNOWLEDGE','FORUM'));
ALTER TABLE asset ADD CONSTRAINT asset_owner_consistency CHECK(
 (owner_type='PROJECT' AND project_id IS NOT NULL AND receipt_order_id IS NULL AND content_id IS NULL AND forum_post_id IS NULL) OR
 (owner_type='RECEIPT' AND receipt_order_id IS NOT NULL AND project_id IS NULL AND content_id IS NULL AND forum_post_id IS NULL) OR
 (owner_type='KNOWLEDGE' AND content_id IS NOT NULL AND project_id IS NULL AND receipt_order_id IS NULL AND forum_post_id IS NULL) OR
 (owner_type='FORUM' AND forum_post_id IS NOT NULL AND project_id IS NULL AND receipt_order_id IS NULL AND content_id IS NULL));
CREATE INDEX asset_forum_post_idx ON asset(forum_post_id,state) WHERE deleted_at IS NULL;
CREATE TABLE forum_post_asset(post_id uuid NOT NULL REFERENCES forum_post(id) ON DELETE RESTRICT,asset_id uuid NOT NULL REFERENCES asset(id) ON DELETE RESTRICT,PRIMARY KEY(post_id,asset_id));
CREATE INDEX forum_post_asset_reverse_idx ON forum_post_asset(asset_id,post_id);
COMMENT ON TABLE forum_post_asset IS '当前正文附件关联；写入锁板块→帖子→按ID排序的资产，文件必须READY且归属同帖；隐藏/删帖后不再签发学生下载';
