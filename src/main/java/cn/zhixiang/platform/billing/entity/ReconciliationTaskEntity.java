package cn.zhixiang.platform.billing.entity;

import java.util.UUID;

/**
 * 内部任务信息。
 *
 * @param id ID
 * @param runId 原运行
 * @param createdBy 原操作管理员
 * @param state 状态
 */
public record ReconciliationTaskEntity(UUID id, UUID runId, UUID createdBy, String state) {}
