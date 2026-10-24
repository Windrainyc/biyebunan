package cn.zhixiang.platform.model;
import java.util.UUID;
import java.time.OffsetDateTime;
import jakarta.validation.constraints.*;
/** 模型价格的请求与响应；金额采用十进制字符串避免浮点精度损失。 */
public final class PriceDtos {
 private PriceDtos() {}
 /** 新价格或未生效草稿。
 * @param modelId 模型标识，创建后不可更改
 * @param currency 币种，仅 CNY
 * @param inputPerMillion 每百万输入词元人民币价格，最多六位小数
 * @param outputPerMillion 每百万输出词元人民币价格
 * @param cachedInputPerMillion 每百万缓存命中词元人民币价格
 * @param effectiveFrom 生效时间，新价格必须在未来；生效后金额不可覆盖
 * @param status 是否可供新运行选择
 */
 public record Write(@NotNull UUID modelId,@NotNull @Pattern(regexp="CNY") String currency,@NotNull @Pattern(regexp="[0-9]{1,14}(\\.[0-9]{1,6})?") String inputPerMillion,@NotNull @Pattern(regexp="[0-9]{1,14}(\\.[0-9]{1,6})?") String outputPerMillion,@NotNull @Pattern(regexp="[0-9]{1,14}(\\.[0-9]{1,6})?") String cachedInputPerMillion,@NotNull OffsetDateTime effectiveFrom,@NotNull ModelDtos.Status status) {}
 /** 价格响应。
 * @param id 唯一标识
 * @param version 并发版本
 * @param createdAt 创建时间
 * @param updatedAt 修改时间
 * @param modelId 模型标识，创建后不可更改
 * @param currency 币种，仅 CNY
 * @param inputPerMillion 每百万输入词元人民币价格，最多六位小数
 * @param outputPerMillion 每百万输出词元人民币价格
 * @param cachedInputPerMillion 每百万缓存命中词元人民币价格
 * @param effectiveFrom 生效时间，新价格必须在未来；生效后金额不可覆盖
 * @param status 是否可供新运行选择
 */
 public record View(UUID id,long version,OffsetDateTime createdAt,OffsetDateTime updatedAt,UUID modelId,String currency,String inputPerMillion,String outputPerMillion,String cachedInputPerMillion,OffsetDateTime effectiveFrom,ModelDtos.Status status) {}
}
