package cn.zhixiang.platform.membership.entity;

import org.apache.ibatis.annotations.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * 套餐权益的数据库不可变版本记录。
 *
 * @param id 权益标识
 * @param planId 套餐
 * @param versionNumber 套餐内序号
 * @param durationDays 天数
 * @param monthlyTokenQuota 月词元
 * @param monthlyAmountLimit 月金额，精度六位
 * @param effectiveFrom 生效
 * @param createdAt 追加
 */
public record PlanVersionEntity(
        UUID id,
        UUID planId,
        long versionNumber,
        int durationDays,
        long monthlyTokenQuota,
        BigDecimal monthlyAmountLimit,
        OffsetDateTime effectiveFrom,
        OffsetDateTime createdAt) {}
