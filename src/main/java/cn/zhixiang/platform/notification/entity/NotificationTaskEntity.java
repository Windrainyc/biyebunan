package cn.zhixiang.platform.notification.entity;

import org.apache.ibatis.annotations.*;

import java.util.*;

/**
 * 内部通知快照，不能接受外部任意接收者请求。
 *
 * @param eventId 领域事件
 * @param recipientId 接收者
 * @param title 标题
 * @param body 正文
 */
public record NotificationTaskEntity(UUID eventId, UUID recipientId, String title, String body) {}
