package cn.zhixiang.platform.project.entity;

import java.util.*;

/**
 * 持久化查询记录，仅供服务层映射和业务判断。
 *
 * @param id 标识
 * @param version CAS
 * @param createdAt 创建
 * @param updatedAt 更新
 * @param name 名称
 * @param dueDate 日期
 * @param weight 权重
 * @param completed 完成状态
 * @param projectId 项目
 */
public record MilestoneEntity(
        UUID id,
        long version,
        java.time.OffsetDateTime createdAt,
        java.time.OffsetDateTime updatedAt,
        String name,
        java.time.LocalDate dueDate,
        int weight,
        boolean completed,
        UUID projectId) {}
