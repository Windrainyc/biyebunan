package cn.zhixiang.platform.billing.entity;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * 内部预占。
 *
 * @param runId 运行
 * @param accountId 账户
 * @param priceId 价格
 * @param tokens 预占词元
 * @param amount 预占金额
 * @param state 状态
 * @param inputTokens 实际输入
 * @param outputTokens 实际输出
 * @param cachedTokens 实际缓存
 * @param actualAmount 实际金额
 * @param createdAt 创建
 * @param updatedAt 更新
 */
public record QuotaHoldEntity(
        UUID runId,
        UUID accountId,
        UUID priceId,
        long tokens,
        BigDecimal amount,
        String state,
        Long inputTokens,
        Long outputTokens,
        Long cachedTokens,
        BigDecimal actualAmount,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {}
