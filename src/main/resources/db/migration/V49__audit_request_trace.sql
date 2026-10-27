-- 审计保留服务端HTTP请求关联ID；历史与后台事件为空，不伪造追踪关系。
ALTER TABLE audit_event ADD COLUMN trace_id varchar(36);
CREATE INDEX audit_event_trace_idx ON audit_event(trace_id) WHERE trace_id IS NOT NULL;
COMMENT ON COLUMN audit_event.trace_id IS '服务端请求UUID，与X-Request-ID及错误traceId一致；历史/后台允许空';
