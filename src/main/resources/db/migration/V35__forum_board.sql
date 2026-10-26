-- 论坛板块基础数据；帖子模块必须通过 RESTRICT 外键引用板块，禁止级联删除历史讨论。
CREATE TABLE forum_board (
 id uuid PRIMARY KEY,
 name varchar(200) NOT NULL CHECK(length(trim(name))>0),
 code varchar(200) NOT NULL UNIQUE CHECK(length(trim(code))>0),
 requires_review boolean NOT NULL,
 sort_order bigint NOT NULL CHECK(sort_order>=0),
 status varchar(16) NOT NULL CHECK(status IN ('ENABLED','DISABLED')),
 version bigint NOT NULL DEFAULT 1 CHECK(version>0),
 created_by uuid NOT NULL REFERENCES user_account(id),
 updated_by uuid NOT NULL REFERENCES user_account(id),
 created_at timestamptz NOT NULL DEFAULT now(),
 updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX forum_board_status_created_idx ON forum_board(status,created_at,id);
COMMENT ON TABLE forum_board IS '新帖创建时锁板块并检查启用与审核开关；停用保留历史，未引用板块可硬删除';
COMMENT ON COLUMN forum_board.requires_review IS '仅决定新提交内容审核策略，不自动发布已待审核内容';
