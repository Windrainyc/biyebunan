package cn.zhixiang.platform.notification.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Update;

/** 有界正文清理；保留去重墓碑，不处理未完成任务。 */
@Mapper
public interface NotificationRetentionMapper {
    /**
     * 按创建时间清理一批通知正文，包含已读与未读；多个worker跳过已锁记录。
     *
     * @param days 已由服务验证的保留天数
     * @return 清理数量，不超过100
     */
    @Update(
            "WITH candidates AS (SELECT id FROM in_app_notification WHERE redacted_at IS NULL AND"
                + " created_at<now()-make_interval(days=>#{days}) ORDER BY created_at,id LIMIT 100"
                + " FOR UPDATE SKIP LOCKED) UPDATE in_app_notification n SET"
                + " title='通知已过保留期',body='历史通知正文已清理',redacted_at=now(),updated_at=now() FROM"
                + " candidates c WHERE n.id=c.id")
    int redactInbox(int days);

    /**
     * 清理已结束任务文案，QUEUED任务始终保留以保证可投递。
     *
     * @param days 保留天数
     * @return 清理数量，不超过100
     */
    @Update(
            "WITH candidates AS (SELECT event_id,recipient_id FROM notification_delivery_task WHERE"
                + " redacted_at IS NULL AND state IN ('DELIVERED','FAILED') AND"
                + " finished_at<now()-make_interval(days=>#{days}) ORDER BY finished_at,event_id"
                + " LIMIT 100 FOR UPDATE SKIP LOCKED) UPDATE notification_delivery_task n SET"
                + " title='通知已过保留期',body='历史通知正文已清理',redacted_at=now() FROM candidates c WHERE"
                + " n.event_id=c.event_id AND n.recipient_id=c.recipient_id")
    int redactTasks(int days);
}
