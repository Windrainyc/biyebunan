-- 评论与举报保留原作者及目标引用，用户删除评论采用软删除。
CREATE TABLE forum_comment (
 id uuid PRIMARY KEY,post_id uuid NOT NULL REFERENCES forum_post(id),author_id uuid NOT NULL REFERENCES user_account(id),
 parent_id uuid,body varchar(5000) NOT NULL CHECK(length(trim(body))>0),
 state varchar(16) NOT NULL DEFAULT 'PUBLISHED' CHECK(state IN ('PUBLISHED','HIDDEN')),
 version bigint NOT NULL DEFAULT 1,created_at timestamptz NOT NULL DEFAULT now(),updated_at timestamptz NOT NULL DEFAULT now(),deleted_at timestamptz,
 UNIQUE(id,post_id),FOREIGN KEY(parent_id,post_id) REFERENCES forum_comment(id,post_id),CHECK(parent_id IS NULL OR parent_id<>id)
);
CREATE INDEX forum_comment_post_idx ON forum_comment(post_id,created_at,id);
CREATE INDEX forum_comment_parent_idx ON forum_comment(parent_id);
CREATE INDEX forum_comment_rate_idx ON forum_comment(author_id,created_at);
CREATE TABLE forum_report (
 id uuid PRIMARY KEY,post_id uuid NOT NULL REFERENCES forum_post(id),reporter_id uuid NOT NULL REFERENCES user_account(id),
 reason varchar(200) NOT NULL CHECK(length(trim(reason))>0),state varchar(16) NOT NULL DEFAULT 'OPEN' CHECK(state IN ('OPEN','RESOLVED','DISMISSED')),
 version bigint NOT NULL DEFAULT 1,created_at timestamptz NOT NULL DEFAULT now(),updated_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(post_id,reporter_id)
);
CREATE INDEX forum_report_state_created_idx ON forum_report(state,created_at,id);
COMMENT ON TABLE forum_comment IS '允许主评论与一层回复；父评论被隐藏/删除时学生不可见其回复；全部写先锁帖子防审核并发穿透';
COMMENT ON TABLE forum_report IS '每人每帖一条永久去重举报；处理结论与帖子治理分离，管理员审核另有原因审计';
