-- 通知在领域事件产生时固定受控渲染版本，后续模板编辑不能改变已排队文案。
ALTER TABLE notification_delivery_task ADD COLUMN template_id uuid REFERENCES notification_template(id) ON DELETE RESTRICT;
ALTER TABLE notification_delivery_task ADD COLUMN template_version bigint;
ALTER TABLE notification_delivery_task ADD COLUMN renderer_code varchar(100);
CREATE INDEX notification_task_template_idx ON notification_delivery_task(template_id) WHERE template_id IS NOT NULL;
COMMENT ON COLUMN notification_delivery_task.renderer_code IS '受控站内渲染器代码；未配置时使用内置默认，禁用或未知渲染器记录失败，不执行任意表达式';
