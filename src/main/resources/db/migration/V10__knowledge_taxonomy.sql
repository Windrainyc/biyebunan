-- 知识分类/标签；后续内容关系必须通过外键引用，不允许删除被引用的字典。
CREATE TABLE category_tree_mutex(id integer PRIMARY KEY CHECK(id=1));
INSERT INTO category_tree_mutex VALUES(1);
CREATE TABLE knowledge_category (
 id uuid PRIMARY KEY,name varchar(200) NOT NULL,code varchar(200) NOT NULL UNIQUE,
 parent_id uuid REFERENCES knowledge_category(id) ON DELETE RESTRICT,sort_order bigint NOT NULL CHECK(sort_order>=0),
 status varchar(16) NOT NULL CHECK(status IN ('ENABLED','DISABLED')),version bigint NOT NULL DEFAULT 1,
 created_by uuid NOT NULL REFERENCES user_account(id),updated_by uuid NOT NULL REFERENCES user_account(id),
 created_at timestamptz NOT NULL DEFAULT now(),updated_at timestamptz NOT NULL DEFAULT now(),CHECK(parent_id IS NULL OR parent_id<>id)
);
CREATE INDEX category_parent_idx ON knowledge_category(parent_id,sort_order,id);
CREATE TABLE knowledge_tag (
 id uuid PRIMARY KEY,name varchar(200) NOT NULL,code varchar(200) NOT NULL UNIQUE,
 status varchar(16) NOT NULL CHECK(status IN ('ENABLED','DISABLED')),version bigint NOT NULL DEFAULT 1,
 created_by uuid NOT NULL REFERENCES user_account(id),updated_by uuid NOT NULL REFERENCES user_account(id),
 created_at timestamptz NOT NULL DEFAULT now(),updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE knowledge_category IS '分类树结构修改需持category_tree_mutex；父子引用RESTRICT，不级联删除';
COMMENT ON TABLE knowledge_tag IS '标签代码唯一；停用不影响已发布内容历史标签，但不得用于新内容关联';
