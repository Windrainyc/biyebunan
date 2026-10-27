-- 追加审计的稳定分页与按资源追溯索引；不建立更新时间/启停等不存在的状态。
CREATE INDEX audit_created_page_idx ON audit_event(created_at DESC,id DESC);
CREATE INDEX audit_resource_page_idx ON audit_event(resource_type,resource_id,created_at DESC,id DESC);
COMMENT ON INDEX audit_created_page_idx IS '只读审计目录按创建时间及ID稳定分页';
COMMENT ON INDEX audit_resource_page_idx IS '业务事件按类型和资源追溯；通配搜索仍需依据规模评估专用索引';
