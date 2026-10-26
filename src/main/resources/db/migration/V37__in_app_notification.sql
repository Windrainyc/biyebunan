-- 收件人私有站内通知；同一事件对同一用户只生成一封，业务事件可至少一次重投。
CREATE TABLE in_app_notification (
 id uuid PRIMARY KEY,
 recipient_id uuid NOT NULL REFERENCES user_account(id),
 event_id uuid NOT NULL,
 title varchar(200) NOT NULL CHECK(length(trim(title))>0),
 body varchar(200) NOT NULL CHECK(length(trim(body))>0),
 read_at timestamptz,
 created_at timestamptz NOT NULL DEFAULT now(),
 updated_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(recipient_id,event_id)
);
CREATE INDEX notification_recipient_created_idx ON in_app_notification(recipient_id,created_at,id);
CREATE INDEX notification_unread_idx ON in_app_notification(recipient_id,created_at,id) WHERE read_at IS NULL;
COMMENT ON TABLE in_app_notification IS '本人收件箱，管理员不具有跨用户读取特权；标题正文不得存放私聊原文、文件签名和凭证';
