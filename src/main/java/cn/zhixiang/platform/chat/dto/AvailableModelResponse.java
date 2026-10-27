package cn.zhixiang.platform.chat.dto;

import java.util.*;

/**
 * 学生可见模型，字段均只读。
 *
 * @param id 模型标识
 * @param name 展示名称
 * @param maxOutputTokens 单次最大输出词元数，实际请求仍受上下文预算限制
 */
public record AvailableModelResponse(UUID id, String name, int maxOutputTokens) {}
