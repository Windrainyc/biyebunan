-- 暂停保留原阶段，学生验收记录与阶段变更处于同一事务。
ALTER TABLE software_project ADD COLUMN paused_from varchar(24);
ALTER TABLE software_project ADD CONSTRAINT project_pause_consistency CHECK (
 (stage='PAUSED' AND paused_from IS NOT NULL AND paused_from IN ('PENDING','REQUIREMENTS','PROPOSAL','DEVELOPMENT','DOCUMENTATION','ACCEPTANCE'))
 OR (stage<>'PAUSED' AND paused_from IS NULL));
CREATE TABLE project_stage_event (
 id uuid PRIMARY KEY,project_id uuid NOT NULL REFERENCES software_project(id),
 from_stage varchar(24) NOT NULL,to_stage varchar(24) NOT NULL,
 reason varchar(1000) NOT NULL,actor_id uuid NOT NULL REFERENCES user_account(id),
 student_decision varchar(16) CHECK(student_decision IN ('ACCEPT','REJECT')),
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX project_stage_event_history_idx ON project_stage_event(project_id,created_at,id);
