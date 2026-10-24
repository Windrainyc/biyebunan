-- 一订单一项目，项目主体不包含交付物可变内容；进度来自里程碑权重。
CREATE TABLE software_project (
 id uuid PRIMARY KEY,order_id uuid NOT NULL UNIQUE REFERENCES software_order(id) ON DELETE RESTRICT,
 title varchar(200) NOT NULL,requirements varchar(20000) NOT NULL,delivery_date date NOT NULL,
 stage varchar(24) NOT NULL DEFAULT 'PENDING' CHECK(stage IN ('PENDING','REQUIREMENTS','PROPOSAL','DEVELOPMENT','DOCUMENTATION','ACCEPTANCE','DONE','PAUSED')),
 version bigint NOT NULL DEFAULT 1,created_by uuid NOT NULL REFERENCES user_account(id),updated_by uuid NOT NULL REFERENCES user_account(id),
 created_at timestamptz NOT NULL DEFAULT now(),updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE project_milestone (
 id uuid PRIMARY KEY,project_id uuid NOT NULL REFERENCES software_project(id) ON DELETE RESTRICT,
 name varchar(200) NOT NULL,due_date date NOT NULL,weight integer NOT NULL CHECK(weight BETWEEN 1 AND 100),completed boolean NOT NULL,
 version bigint NOT NULL DEFAULT 1,created_at timestamptz NOT NULL DEFAULT now(),updated_at timestamptz NOT NULL DEFAULT now(),
 created_by uuid NOT NULL REFERENCES user_account(id),updated_by uuid NOT NULL REFERENCES user_account(id),UNIQUE(project_id,name)
);
CREATE INDEX milestone_project_idx ON project_milestone(project_id,due_date,id);
COMMENT ON TABLE project_milestone IS '所有写入先锁订单再锁项目，项目下权重总和不得超过100；完成权重之和为进度';
