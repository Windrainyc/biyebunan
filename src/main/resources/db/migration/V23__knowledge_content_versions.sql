-- 知识正文与不可覆盖的发布快照；所有学生查询先按当前权限过滤，再分页。
CREATE TABLE knowledge_content (
 id uuid PRIMARY KEY,title varchar(200) NOT NULL,category_id uuid NOT NULL REFERENCES knowledge_category(id),
 type varchar(16) NOT NULL CHECK(type IN ('VIDEO','DOCUMENT','MARKDOWN')),asset_id uuid REFERENCES asset(id),markdown varchar(200000),
 audience varchar(16) NOT NULL CHECK(audience IN ('PUBLIC','MEMBER','PROJECT')),project_id uuid REFERENCES software_project(id),
 state varchar(16) NOT NULL DEFAULT 'DRAFT' CHECK(state IN ('DRAFT','REVIEW','PUBLISHED','WITHDRAWN')),
 published_version_id uuid,index_state varchar(16) NOT NULL DEFAULT 'PENDING' CHECK(index_state IN ('PENDING','INDEXING','READY','FAILED')),
 index_generation bigint NOT NULL DEFAULT 0,deleted_at timestamptz,version bigint NOT NULL DEFAULT 1,
 created_at timestamptz NOT NULL DEFAULT now(),updated_at timestamptz NOT NULL DEFAULT now(),
 created_by uuid NOT NULL REFERENCES user_account(id),updated_by uuid NOT NULL REFERENCES user_account(id),
 CHECK((audience='PROJECT' AND project_id IS NOT NULL) OR (audience<>'PROJECT' AND project_id IS NULL))
);
CREATE TABLE knowledge_content_tag(content_id uuid NOT NULL REFERENCES knowledge_content(id),tag_id uuid NOT NULL REFERENCES knowledge_tag(id),PRIMARY KEY(content_id,tag_id));
CREATE TABLE knowledge_version (
 id uuid PRIMARY KEY,content_id uuid NOT NULL REFERENCES knowledge_content(id),version_number bigint NOT NULL,
 title varchar(200) NOT NULL,category_id uuid NOT NULL REFERENCES knowledge_category(id),type varchar(16) NOT NULL,
 asset_id uuid REFERENCES asset(id),markdown varchar(200000),audience varchar(16) NOT NULL,project_id uuid REFERENCES software_project(id),
 created_at timestamptz NOT NULL DEFAULT now(),created_by uuid NOT NULL REFERENCES user_account(id),UNIQUE(content_id,version_number)
);
CREATE TABLE knowledge_version_tag(version_id uuid NOT NULL REFERENCES knowledge_version(id),tag_id uuid NOT NULL REFERENCES knowledge_tag(id),PRIMARY KEY(version_id,tag_id));
ALTER TABLE knowledge_content ADD CONSTRAINT knowledge_published_version_fk FOREIGN KEY(published_version_id) REFERENCES knowledge_version(id);
CREATE TABLE knowledge_event(id uuid PRIMARY KEY,content_id uuid NOT NULL REFERENCES knowledge_content(id),action varchar(32) NOT NULL,reason varchar(1000) NOT NULL,actor_id uuid NOT NULL REFERENCES user_account(id),created_at timestamptz NOT NULL DEFAULT now());
CREATE INDEX knowledge_content_page_idx ON knowledge_content(state,created_at DESC,id DESC) WHERE deleted_at IS NULL;
CREATE INDEX knowledge_content_project_idx ON knowledge_content(project_id,state) WHERE deleted_at IS NULL;
CREATE INDEX knowledge_content_tag_reverse_idx ON knowledge_content_tag(tag_id,content_id);
CREATE INDEX knowledge_version_page_idx ON knowledge_version(content_id,created_at DESC,id DESC);
CREATE FUNCTION protect_knowledge_version() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'knowledge version is immutable' USING ERRCODE='23514'; END; $$;
CREATE TRIGGER knowledge_version_immutable BEFORE UPDATE OR DELETE ON knowledge_version FOR EACH ROW EXECUTE FUNCTION protect_knowledge_version();
-- 扩展资产归属；只替换明确涉及owner_type的原约束，保留摘要/大小/READY条件。
ALTER TABLE asset ADD COLUMN content_id uuid REFERENCES knowledge_content(id);
DO $$ DECLARE c record; BEGIN FOR c IN SELECT conname FROM pg_constraint WHERE conrelid='asset'::regclass AND contype='c' AND pg_get_constraintdef(oid) LIKE '%owner_type%' LOOP EXECUTE format('ALTER TABLE asset DROP CONSTRAINT %I',c.conname); END LOOP; END; $$;
ALTER TABLE asset ADD CONSTRAINT asset_owner_type_check CHECK(owner_type IN ('PROJECT','RECEIPT','KNOWLEDGE'));
ALTER TABLE asset ADD CONSTRAINT asset_owner_consistency CHECK(
 (owner_type='PROJECT' AND project_id IS NOT NULL AND receipt_order_id IS NULL AND content_id IS NULL) OR
 (owner_type='RECEIPT' AND receipt_order_id IS NOT NULL AND project_id IS NULL AND content_id IS NULL) OR
 (owner_type='KNOWLEDGE' AND content_id IS NOT NULL AND project_id IS NULL AND receipt_order_id IS NULL));
CREATE INDEX asset_content_idx ON asset(content_id,state) WHERE deleted_at IS NULL;
COMMENT ON TABLE knowledge_version IS '发布时写入的不可变内容快照；历史读取仍须当前内容权限与历史快照权限同时满足';
