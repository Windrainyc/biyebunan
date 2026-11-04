ALTER TABLE asset ADD COLUMN profile_user_id uuid REFERENCES user_account(id);
ALTER TABLE user_account ADD COLUMN avatar_index integer NOT NULL DEFAULT floor(random()*10)::integer CHECK(avatar_index BETWEEN 0 AND 9), ADD COLUMN avatar_asset_id uuid REFERENCES asset(id);
ALTER TABLE asset DROP CONSTRAINT asset_owner_type_check;
ALTER TABLE asset DROP CONSTRAINT asset_owner_consistency;
ALTER TABLE asset ADD CONSTRAINT asset_owner_type_check CHECK(owner_type IN ('PROJECT','RECEIPT','KNOWLEDGE','FORUM','MATERIAL','PROFILE'));
ALTER TABLE asset ADD CONSTRAINT asset_owner_consistency CHECK(
(owner_type='PROJECT' AND project_id IS NOT NULL AND num_nonnulls(project_id,receipt_order_id,content_id,forum_post_id,material_category_id,profile_user_id)=1) OR
(owner_type='RECEIPT' AND receipt_order_id IS NOT NULL AND num_nonnulls(project_id,receipt_order_id,content_id,forum_post_id,material_category_id,profile_user_id)=1) OR
(owner_type='KNOWLEDGE' AND content_id IS NOT NULL AND num_nonnulls(project_id,receipt_order_id,content_id,forum_post_id,material_category_id,profile_user_id)=1) OR
(owner_type='FORUM' AND forum_post_id IS NOT NULL AND num_nonnulls(project_id,receipt_order_id,content_id,forum_post_id,material_category_id,profile_user_id)=1) OR
(owner_type='MATERIAL' AND material_category_id IS NOT NULL AND num_nonnulls(project_id,receipt_order_id,content_id,forum_post_id,material_category_id,profile_user_id)=1) OR
(owner_type='PROFILE' AND profile_user_id IS NOT NULL AND num_nonnulls(project_id,receipt_order_id,content_id,forum_post_id,material_category_id,profile_user_id)=1));
