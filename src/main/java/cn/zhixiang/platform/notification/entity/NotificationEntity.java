package cn.zhixiang.platform.notification.entity;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * 持久化行，不作为接口直接返回。
 *
 * @param id 通知
 * @param title 标题
 * @param body 正文
 * @param readAt 已读时间，未读为空
 * @param announcementId 关联公告，可空表示业务提醒
 * @param createdAt 创建时间
 */
public record NotificationEntity(
        UUID id,
        String title,
        String body,
        OffsetDateTime readAt,
        OffsetDateTime createdAt,
        UUID announcementId) {}
