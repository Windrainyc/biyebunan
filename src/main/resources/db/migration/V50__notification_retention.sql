-- 保留去重标识和原文摘要，清除正文后不会因领域事件重放重新显示历史内容。
ALTER TABLE in_app_notification ADD COLUMN content_digest char(64);
ALTER TABLE in_app_notification ADD COLUMN redacted_at timestamptz;
UPDATE in_app_notification SET content_digest=encode(sha256(convert_to(jsonb_build_array(title,body)::text,'UTF8')),'hex');
ALTER TABLE in_app_notification ALTER COLUMN content_digest SET NOT NULL;
ALTER TABLE notification_delivery_task ADD COLUMN content_digest char(64);
ALTER TABLE notification_delivery_task ADD COLUMN redacted_at timestamptz;
UPDATE notification_delivery_task SET content_digest=encode(sha256(convert_to(jsonb_build_array(title,body)::text,'UTF8')),'hex');
ALTER TABLE notification_delivery_task ALTER COLUMN content_digest SET NOT NULL;
-- 数据库插入时统一生成摘要，避免多个写入口及历史工具遗漏；更新正文不能改变原摘要。
CREATE FUNCTION notification_content_digest() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 NEW.content_digest := encode(sha256(convert_to(jsonb_build_array(NEW.title,NEW.body)::text,'UTF8')),'hex');
 RETURN NEW;
END;
$$;
CREATE TRIGGER notification_content_digest_insert BEFORE INSERT ON in_app_notification FOR EACH ROW EXECUTE FUNCTION notification_content_digest();
CREATE TRIGGER notification_task_digest_insert BEFORE INSERT ON notification_delivery_task FOR EACH ROW EXECUTE FUNCTION notification_content_digest();
CREATE INDEX notification_retention_idx ON in_app_notification(created_at,id) WHERE redacted_at IS NULL;
CREATE INDEX notification_task_retention_idx ON notification_delivery_task(finished_at,event_id) WHERE redacted_at IS NULL AND state IN ('DELIVERED','FAILED');
COMMENT ON COLUMN in_app_notification.redacted_at IS '保留期正文清理时间；唯一事件键和摘要继续保留，避免重复通知复活';
