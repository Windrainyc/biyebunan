package cn.zhixiang.platform.operations.dto;

import org.springframework.transaction.annotation.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 安全审计响应，不输出通用detail JSON。
 *
 * @param id 审计ID
 * @param actorId 操作者
 * @param action 动作
 * @param targetId 资源标识
 * @param resourceType 资源类型
 * @param reason 可空业务理由
 * @param traceId 未持久化追踪编号时为null，不推测生成
 * @param createdAt 追加时间
 */
public record AuditResponse(
        UUID id,
        UUID actorId,
        String action,
        String targetId,
        String resourceType,
        String reason,
        String traceId,
        OffsetDateTime createdAt) {}
