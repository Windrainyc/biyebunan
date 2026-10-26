package cn.zhixiang.platform.billing;
import java.util.UUID;
import java.time.OffsetDateTime;
import jakarta.validation.constraints.*;
/** 财务调整输入输出；金额单位人民币元，禁止二进制浮点数。 */
public final class AdjustmentDtos {
 private AdjustmentDtos(){}
 /** 调整方向。 */
 public enum Direction { /** 增加已发生费用。 */ DEBIT, /** 退还部分已发生费用。 */ CREDIT }
 /** 人工裁决输入，不能用于释放未知预占。
  * @param runId 已结算运行
  * @param direction 增扣或退还
  * @param amount 正数十进制字符串，最多6位小数
  * @param reason 非空调整原因
  * @param evidenceRef 人工核验后的证据编号，不自动访问外部URL */
 public record Write(@NotNull UUID runId,@NotNull Direction direction,@NotBlank @Pattern(regexp="[0-9]{1,14}(\\.[0-9]{1,6})?") String amount,@NotBlank @Size(max=1000) String reason,@NotBlank @Size(max=1000) String evidenceRef){}
 /** 只读调整单，原始用量保留在UsageEntry。
  * @param id 调整ID
  * @param runId 原运行
  * @param direction 方向
  * @param amount 正金额字符串
  * @param currency CNY
  * @param reason 原因
  * @param evidenceRef 证据编号
  * @param createdBy 操作管理员
  * @param createdAt 创建时间 */
 public record View(UUID id,UUID runId,String direction,String amount,String currency,String reason,String evidenceRef,UUID createdBy,OffsetDateTime createdAt){}
}
