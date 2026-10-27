package cn.zhixiang.platform.forum.entity;

import org.apache.ibatis.annotations.*;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * 持久化行。
 *
 * @param id 标识
 * @param boardId 板块
 * @param authorId 作者
 * @param title 标题
 * @param body 正文
 * @param state 审核状态
 * @param pinned 置顶
 * @param version 版本
 * @param createdAt 创建
 * @param updatedAt 更新
 * @param deletedAt 软删时间
 */
public record PostEntity(
        UUID id,
        UUID boardId,
        UUID authorId,
        String title,
        String body,
        String state,
        boolean pinned,
        long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        OffsetDateTime deletedAt) {}
