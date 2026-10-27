-- 有界日期统计索引：按实际业务发生时间读取主库，不复用缺少归属的全局事件投影。
CREATE INDEX project_created_statistics_idx ON software_project(created_at,order_id);
CREATE INDEX delivery_event_statistics_idx ON delivery_event(created_at,delivery_id) WHERE action='PUBLISHED';
CREATE INDEX quota_ledger_statistics_idx ON quota_ledger(created_at,account_id) WHERE kind IN ('SETTLE','ADJUSTMENT');
CREATE INDEX quota_hold_unknown_statistics_idx ON quota_hold(created_at,account_id) WHERE state='RECONCILING';
CREATE INDEX audit_active_student_statistics_idx ON audit_event(created_at,actor_id) WHERE action='CREATED' AND resource_type IN ('forum_post','forum_comment','generation_run');
