package cn.zhixiang.platform.billing.dto;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * 学生额度视图。
 *
 * @param availableTokens 当前可用词元
 * @param reservedTokens 冻结词元
 * @param availableAmount 可用CNY金额
 * @param reservedAmount 冻结CNY金额
 * @param currency CNY
 * @param periodEnd 当前周期或权益最早到期
 */
public record QuotaResponse(
        long availableTokens,
        long reservedTokens,
        String availableAmount,
        String reservedAmount,
        String currency,
        OffsetDateTime periodEnd) {}
