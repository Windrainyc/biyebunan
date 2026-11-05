package cn.zhixiang.platform.forum.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 学生论坛列表查询行，作者仅公开昵称和默认头像。
 *
 * @param id 帖子标识
 * @param title 标题
 * @param body 正文
 * @param boardId 板块标识
 * @param boardName 板块名称
 * @param authorId 作者标识
 * @param authorName 公开昵称
 * @param avatarIndex 默认头像序号
 * @param state 审核状态
 * @param createdAt 创建时间
 * @param replyCount 当前可见回复数
 * @param heat 近七天回复数乘三加独立回复人数乘五
 */
public record ForumFeedResponse(
        UUID id,
        String title,
        String body,
        UUID boardId,
        String boardName,
        UUID authorId,
        String authorName,
        int avatarIndex,
        String state,
        OffsetDateTime createdAt,
        long replyCount,
        long heat) {}
