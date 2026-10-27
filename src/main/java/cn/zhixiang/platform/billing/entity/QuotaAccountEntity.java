package cn.zhixiang.platform.billing.entity;

import org.apache.ibatis.annotations.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * 内部额度账户。
 *
 * @param id ID
 * @param userId 用户
 * @param periodStart UTC月起点
 * @param periodEnd UTC月终点
 * @param grantedTokens 已授予
 * @param reservedTokens 已冻结
 * @param spentTokens 已使用
 * @param grantedAmount 已授予金额
 * @param reservedAmount 冻结金额
 * @param spentAmount 已用金额
 * @param version 版本
 * @param createdAt 创建
 * @param updatedAt 更新
 */
public record QuotaAccountEntity(
        UUID id,
        UUID userId,
        OffsetDateTime periodStart,
        OffsetDateTime periodEnd,
        long grantedTokens,
        long reservedTokens,
        long spentTokens,
        BigDecimal grantedAmount,
        BigDecimal reservedAmount,
        BigDecimal spentAmount,
        long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {}
