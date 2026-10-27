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
     * @param reason 核验依据，不能含密钥
     */
    public record Write(
            @NotBlank @Size(max = 200) String reference,
            @NotNull @PastOrPresent OffsetDateTime receivedAt,
            @NotBlank @Pattern(regexp = "[0-9]{1,14}(\\.[0-9]{1,6})?") String amount,
            @NotNull UUID proofAssetId,
            @NotBlank @Size(max = 1000) String reason) {}
}
