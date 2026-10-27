package cn.zhixiang.platform.forum.entity;

import org.apache.ibatis.annotations.*;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * 内部持久化行。
 *
 * @param id 标识
 * @param postId 帖子
 * @param authorId 作者
 * @param parentId 父评论
 * @param body 正文
 * @param state 状态
 * @param version 版本
 * @param createdAt 创建
 * @param updatedAt 更新
 * @param deletedAt 软删
 */
public record DiscussionCommentRowEntity(
        UUID id,
        UUID postId,
        UUID authorId,
        UUID parentId,
        String body,
        String state,
        long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        OffsetDateTime deletedAt) {}
