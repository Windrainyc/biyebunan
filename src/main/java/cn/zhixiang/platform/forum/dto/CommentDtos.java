package cn.zhixiang.platform.forum.dto;

import jakarta.validation.constraints.*;

import java.time.OffsetDateTime;
import java.util.*;

/** 评论协议。 */
public final class CommentDtos {
    private CommentDtos() {}

    /**
     * 新评论。
     *
     * @param body 正文
     * @param parentId 主评论ID或空，最多一层回复
     */
    public record Write(@NotBlank @Size(max = 5000) String body, UUID parentId) {}

    /**
     * 评论响应。
     *
     * @param id 标识
     * @param version 版本
     * @param createdAt 创建
     * @param updatedAt 更新
     * @param body 正文
     * @param parentId 主评论
     * @param postId 帖子
     * @param authorId 作者
     * @param state 状态
     */
    public record View(
            UUID id,
            long version,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            String body,
            UUID parentId,
            UUID postId,
            UUID authorId,
            String state) {}
}
