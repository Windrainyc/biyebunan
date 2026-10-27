package cn.zhixiang.platform.chat.entity;

import org.apache.ibatis.annotations.*;

import java.util.*;

/**
 * 历史消息。
 *
 * @param role USER/ASSISTANT
 * @param content 正文
 */
public record RunHistoryEntity(String role, String content) {}
