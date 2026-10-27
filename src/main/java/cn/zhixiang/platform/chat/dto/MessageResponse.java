package cn.zhixiang.platform.chat.dto;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * 历史响应，不暴露运行上下文或供应商密钥。
 *
 * @param id 消息ID
 * @param conversationId 会话
 * @param runId 运行
 * @param role 角色
 * @param text 完整正文
 * @param citations 引用数组
 * @param createdAt 创建时间
 */
public record MessageResponse(
        UUID id,
        UUID conversationId,
        UUID runId,
        String role,
        String text,
        tools.jackson.databind.JsonNode citations,
        OffsetDateTime createdAt) {}
