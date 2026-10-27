package cn.zhixiang.platform.order.entity;

import org.apache.ibatis.annotations.*;

import java.util.UUID;

/**
 * 挑战内部数据。
 *
 * @param orderId 订单
 * @param actorId 学生
 * @param phoneDigest 手机摘要
 * @param codeDigest 验证摘要
 * @param state 发送/消费状态
 * @param attempts 错误次数
 * @param alive 是否未过期
 */
public record BindingEntity(
        UUID orderId,
        UUID actorId,
        String phoneDigest,
        String codeDigest,
        String state,
        int attempts,
        boolean alive) {}
