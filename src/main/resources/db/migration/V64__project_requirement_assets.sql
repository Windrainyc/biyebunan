CREATE TABLE project_requirement_asset (
 project_id uuid NOT NULL REFERENCES software_project(id), asset_id uuid NOT NULL REFERENCES asset(id),
 PRIMARY KEY(project_id,asset_id)
);
CREATE INDEX project_requirement_asset_reference_idx ON project_requirement_asset(asset_id);
COMMENT ON TABLE project_requirement_asset IS '项目需求素材引用，下载仍须当前项目学生身份，素材扫描与删除保护保留';
