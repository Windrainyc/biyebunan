package cn.zhixiang.platform.chat.entity;

import org.apache.ibatis.annotations.*;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * 内部行。
 *
 * @param id 消息
 * @param conversationId 会话
 * @param runId 运行
 * @param role USER/ASSISTANT
 * @param text 正文
 * @param citations 引用JSON数组
 * @param createdAt 创建时间
 */
public record MessageEntity(
        UUID id,
        UUID conversationId,
        UUID runId,
        String role,
        String text,
        String citations,
        OffsetDateTime createdAt) {}
