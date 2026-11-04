package cn.zhixiang.platform.order.dto;

import jakarta.validation.constraints.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 人工收款核验输入，不接受客户端直接设置订单收款状态。 */
public final class ReceiptDtos {
    private ReceiptDtos() {}

    /**
     * 全额核验材料。
     *
     * @param reference 真实收款流水标识，全局唯一
     * @param receivedAt 实际收款时间，不得在未来
     * @param amount CNY金额字符串，最多六位小数
     * @param proofAssetId 当前订单已扫描通过的凭证资产
     * @param channel 可选支付渠道
     * @param reason 核验依据，不能含密钥
     */
    public record Write(
            @Size(max = 200) String reference,
            @PastOrPresent OffsetDateTime receivedAt,
            @Pattern(regexp = "[0-9]{1,14}(\\.[0-9]{1,6})?") String amount,
            UUID proofAssetId,
            @Size(max = 1000) String reason,
            @Pattern(regexp = "WECHAT|ALIPAY|BANK_CARD") String channel) {
        /**
         * 兼容原有调用，未提供支付渠道。
         *
         * @param reference 流水号
         * @param receivedAt 收款时间
         * @param amount 实付金额
         * @param proofAssetId 可选凭证
         * @param reason 可选说明
         */
        public Write(
                String reference,
                OffsetDateTime receivedAt,
                String amount,
                UUID proofAssetId,
                String reason) {
            this(reference, receivedAt, amount, proofAssetId, reason, null);
        }
    }
}
