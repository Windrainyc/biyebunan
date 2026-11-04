package cn.zhixiang.platform.operations.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 内部活动记录投影。
 *
 * @param id 事件
 * @param actorId 用户
 * @param displayName 名称
 * @param platform 平台
 * @param kind 类型
 * @param path 路径
 * @param contentId 知识标识
 * @param contentTitle 标题
 * @param seconds 活跃秒数
 * @param ip 观测地址
 * @param createdAt 记录时间
 */
public record ActivityEntity(
        UUID id,
        UUID actorId,
        String displayName,
        String platform,
        String kind,
        String path,
        UUID contentId,
        String contentTitle,
        int seconds,
        String ip,
        OffsetDateTime createdAt) {}
