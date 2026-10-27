package cn.zhixiang.platform.billing.entity;

import org.apache.ibatis.annotations.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * 权益快照。
 *
 * @param id 会员
 * @param tokens 月词元额度
 * @param amount 月金额额度
 * @param expiresAt 会员到期
 */
public record QuotaBenefitEntity(
        UUID id, long tokens, BigDecimal amount, OffsetDateTime expiresAt) {}
