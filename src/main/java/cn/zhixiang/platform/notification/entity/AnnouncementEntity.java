package cn.zhixiang.platform.notification.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 公告持久化记录。
 *
 * @param id 主键
 * @param title 标题
 * @param body 不可变纯文本正文
 * @param kind 类型
 * @param audience 范围
 * @param createdAt 发布时间
 */
public record AnnouncementEntity(
        UUID id,
        String title,
        String body,
        String kind,
        String audience,
        OffsetDateTime createdAt) {}
