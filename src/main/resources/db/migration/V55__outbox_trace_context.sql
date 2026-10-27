-- 异步投递跨进程恢复父Span，仅存固定长度W3C标识，不存baggage或业务正文。
ALTER TABLE outbox_event ADD COLUMN traceparent varchar(55) CHECK(traceparent ~ '^00-[0-9a-f]{32}-[0-9a-f]{16}-0[01]$');
ALTER TABLE generation_run ADD COLUMN traceparent varchar(55) CHECK(traceparent ~ '^00-[0-9a-f]{32}-[0-9a-f]{16}-0[01]$');
