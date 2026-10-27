package cn.zhixiang.platform.forum.entity;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * 内部持久化行。
 *
 * @param id 标识
 * @param postId 帖子
 * @param reporterId 举报人
 * @param reason 理由
 * @param state 状态
 * @param version 版本
 * @param createdAt 创建
 * @param updatedAt 更新
 */
public record DiscussionReportRowEntity(
        UUID id,
        UUID postId,
        UUID reporterId,
        String reason,
        String state,
        long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {}
