package cn.zhixiang.platform.exporting.entity;

import java.util.UUID;

/**
 * 仅包含当前导出尝试对象。
 *
 * @param jobId 任务
 * @param generation 尝试代次
 * @param objectKey 唯一键
 * @param objectVersion 可空，未知时HEAD定位版本
 * @param storageId 私有配置
 */
public record ExportCleanupCandidateEntity(
        UUID jobId, long generation, String objectKey, String objectVersion, UUID storageId) {}
