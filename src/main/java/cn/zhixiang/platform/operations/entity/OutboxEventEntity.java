package cn.zhixiang.platform.operations.entity;

import java.util.*;

/**
 * 待投递领域事件的内部表示。
 *
 * @param id 事件标识
 * @param aggregateId 聚合标识
 * @param eventType 类型
 * @param payload JSON数据
 * @param generation fencing代数
 */
public record OutboxEventEntity(
        UUID id, UUID aggregateId, String eventType, String payload, long generation) {}
