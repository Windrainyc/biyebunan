package cn.zhixiang.platform.chat.entity;

import java.util.*;

/**
 * 内部配置投影，不直接返回客户端。
 *
 * @param id 模型ID
 * @param name 名称
 * @param endpoint 内部调用地址
 * @param maxOutputTokens 配置输出上限
 */
public record AvailableModelEntity(UUID id, String name, String endpoint, int maxOutputTokens) {}
