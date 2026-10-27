-- 帖子正文按纯文本/Markdown保存；客户端渲染必须禁用原始HTML并净化链接。
CREATE TABLE forum_post (
 id uuid PRIMARY KEY,board_id uuid NOT NULL REFERENCES forum_board(id) ON DELETE RESTRICT,
 author_id uuid NOT NULL REFERENCES user_account(id),title varchar(200) NOT NULL CHECK(length(trim(title))>0),
 body text NOT NULL CHECK(length(trim(body)) BETWEEN 1 AND 20000),
 state varchar(16) NOT NULL CHECK(state IN ('PENDING','PUBLISHED','HIDDEN','LOCKED')),
 pinned boolean NOT NULL DEFAULT false,version bigint NOT NULL DEFAULT 1,
 created_at timestamptz NOT NULL DEFAULT now(),updated_at timestamptz NOT NULL DEFAULT now(),deleted_at timestamptz
);
CREATE INDEX forum_post_board_created_idx ON forum_post(board_id,created_at,id) WHERE deleted_at IS NULL;
CREATE INDEX forum_post_author_created_idx ON forum_post(author_id,created_at,id);
CREATE TABLE forum_post_tag(post_id uuid NOT NULL REFERENCES forum_post(id) ON DELETE RESTRICT,tag_id uuid NOT NULL REFERENCES knowledge_tag(id) ON DELETE RESTRICT,PRIMARY KEY(post_id,tag_id));
CREATE INDEX forum_post_tag_reverse_idx ON forum_post_tag(tag_id,post_id);
COMMENT ON TABLE forum_post IS '学生仅可见启用板块下已发布/锁定帖及本人待审帖；隐藏帖仅管理员可见；删除为软删除';
COMMENT ON TABLE forum_post_tag IS '关联修改与标签停用删除共同持category_tree_mutex；标签外键拒绝被引用标签删除';
