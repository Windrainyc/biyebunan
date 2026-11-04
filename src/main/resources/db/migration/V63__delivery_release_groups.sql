ALTER TABLE project_delivery ADD COLUMN release_id uuid;
ALTER TABLE project_delivery ADD COLUMN release_number integer CHECK(release_number>0);
CREATE INDEX project_delivery_release_idx ON project_delivery(project_id,release_number);
COMMENT ON COLUMN project_delivery.release_id IS '同次原子发布的文件批次，历史单文件版本为空';
