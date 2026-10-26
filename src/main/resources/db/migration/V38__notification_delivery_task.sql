-- 发布事务只追加任务，不在持有订单/项目锁时获取收件人外键锁，避免与学生写操作反向等待。
CREATE TABLE notification_delivery_task (
 event_id uuid NOT NULL, recipient_id uuid NOT NULL,
 title varchar(200) NOT NULL, body varchar(200) NOT NULL,
 state varchar(16) NOT NULL DEFAULT 'QUEUED' CHECK(state IN ('QUEUED','DELIVERED','FAILED')),
 error_code varchar(100), created_at timestamptz NOT NULL DEFAULT now(),finished_at timestamptz,
 PRIMARY KEY(event_id,recipient_id)
);
CREATE INDEX notification_delivery_pending_idx ON notification_delivery_task(created_at,event_id) WHERE state='QUEUED';
COMMENT ON COLUMN notification_delivery_task.recipient_id IS '领域事务产生的接收者快照，不设置账户外键以避免锁反转；worker投递时检验，最终通知有真实账户外键';
COMMENT ON TABLE notification_delivery_task IS '事务型通知outbox；worker短事务SKIP LOCKED领取、插入去重收件箱、完成任务原子提交，进程崩溃回滚即可重做';
