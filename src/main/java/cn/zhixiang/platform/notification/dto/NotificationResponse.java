package cn.zhixiang.platform.notification.dto;

import org.springframework.transaction.annotation.*;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * 本人通知响应。
 *
 * @param id 只读标识
 * @param title 安全短标题
 * @param body 安全短正文
 * @param read 已读标志
 * @param announcementId 关联公告，可空表示业务提醒
 * @param createdAt 创建时间
 * @param eventType 业务事件类型，公告可空
 * @param targetId 业务目标标识，访问时重新验权
 * @param projectId 关联项目标识，可空，访问时仍须重新验权
 */
public record NotificationResponse(
        UUID id,
        String title,
        String body,
        boolean read,
        OffsetDateTime createdAt,
        UUID announcementId,
        UUID projectId,
        String eventType,
        UUID targetId) {}
