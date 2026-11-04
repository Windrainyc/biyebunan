package cn.zhixiang.platform.knowledge.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 知识处理历史，只向获准管理员展示。
 *
 * @param id 事件标识
 * @param action 业务动作
 * @param reason 操作原因
 * @param actorName 操作者显示名
 * @param createdAt 操作时间
 */
public record ContentEventEntity(
        UUID id, String action, String reason, String actorName, OffsetDateTime createdAt) {}
