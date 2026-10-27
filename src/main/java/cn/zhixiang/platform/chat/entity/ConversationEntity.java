package cn.zhixiang.platform.chat.entity;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * 内部数据。
 *
 * @param id ID
 * @param version 版本
 * @param createdAt 创建
 * @param updatedAt 更新时间
 * @param projectId 项目
 * @param title 标题
 * @param studentId 学生
 */
public record ConversationEntity(
        UUID id,
        long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        UUID projectId,
        String title,
        UUID studentId) {}
