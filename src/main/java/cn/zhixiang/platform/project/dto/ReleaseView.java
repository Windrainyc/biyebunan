package cn.zhixiang.platform.project.dto;

import java.util.UUID;

/**
 * 发布批次摘要。
 *
 * @param id 批次标识
 * @param projectId 项目标识
 * @param sequence 发布顺序
 * @param label 展示版本号
 * @param note 更新说明
 * @param createdAt 发布时间
 */
public record ReleaseView(
        UUID id,
        UUID projectId,
        int sequence,
        String label,
        String note,
        java.time.OffsetDateTime createdAt) {}
