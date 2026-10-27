package cn.zhixiang.platform.billing.dto;

import org.springframework.transaction.annotation.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 账务只读投影，金额为十进制人民币字符串；未确认的实际用量和金额为null。
 *
 * @param id 账单标识，当前与运行ID相同
 * @param runId 运行
 * @param inputTokens 实际输入词元，可空
 * @param outputTokens 实际输出词元，可空
 * @param cachedTokens 输入中的缓存词元，可空
 * @param amount 实际金额；RELEASED为0，未知为null
 * @param adjustmentAmount 累计人工调整净额，退还为负，未调整为0
 * @param netAmount 调整后实际费用，未知为null
 * @param currency 固定CNY
 * @param priceVersionId 不可变价格快照
 * @param state RESERVED/RECONCILING/SETTLED/RELEASED
 * @param evidenceSource ORIGINAL_CALL/MANUAL_ADJUDICATION/UNCONFIRMED，人工裁决不是供应商实测
 * @param createdAt 预占创建时间
 */
public record UsageResponse(
        UUID id,
        UUID runId,
        Long inputTokens,
        Long outputTokens,
        Long cachedTokens,
        String amount,
        String adjustmentAmount,
        String netAmount,
        String currency,
        UUID priceVersionId,
        String state,
        OffsetDateTime createdAt,
        String evidenceSource) {}
