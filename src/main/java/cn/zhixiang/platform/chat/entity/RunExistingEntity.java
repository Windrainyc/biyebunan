package cn.zhixiang.platform.chat.entity;

import org.apache.ibatis.annotations.*;

import java.util.*;

/**
 * 永久ID记录。
 *
 * @param id 运行
 * @param conversationId 会话
 * @param requestHash 请求摘要
 */
public record RunExistingEntity(UUID id, UUID conversationId, String requestHash) {}
