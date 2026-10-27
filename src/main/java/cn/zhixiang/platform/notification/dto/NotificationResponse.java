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
 * @param createdAt 创建时间
 */
public record NotificationResponse(
        UUID id, String title, String body, boolean read, OffsetDateTime createdAt) {}
