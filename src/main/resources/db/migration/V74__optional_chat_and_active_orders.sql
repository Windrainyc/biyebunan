-- 普通答疑不强制关联项目，关联项目的旧会话继续校验订单归属。
ALTER TABLE chat_conversation ALTER COLUMN project_id DROP NOT NULL;
-- 仅影响新建订单，不批量改变已有订单及其审计历史。
ALTER TABLE software_order ALTER COLUMN status SET DEFAULT 'ACTIVE';
