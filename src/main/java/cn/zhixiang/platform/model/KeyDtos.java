package cn.zhixiang.platform.model;
import java.util.UUID;
import java.time.OffsetDateTime;
import jakarta.validation.constraints.*;
/** 模型凭证只写请求和脱敏响应；不提供解密查询接口。 */
public final class KeyDtos {
 private KeyDtos() {}
 /** 新凭证，创建为停用状态，需要显式启用。
 * @param name 凭证名称
 * @param modelId 所属模型，创建后不可更改
 * @param secretValue 只写密钥，禁止日志、审计和响应回显 */
 public record Write(@NotBlank @Size(max=200) String name,@NotNull UUID modelId,@NotBlank @Size(max=4096) String secretValue) {
  /** 日志安全字符串。
   * @return 不含密钥的说明 */
  @Override public String toString(){return "KeyWrite[redacted]";}
 }
 /** 密钥轮换。
 * @param secretValue 新密钥，禁止明文持久化
 * @param reason 轮换原因，不应填写密钥 */
 public record Rotate(@NotBlank @Size(max=4096) String secretValue,@NotBlank @Size(max=200) String reason) {
  /** 日志安全字符串。
   * @return 不含密钥和原因的说明 */
  @Override public String toString(){return "KeyRotate[redacted]";}
 }
 /** 脱敏凭证元数据。
 * @param id 唯一标识
 * @param version 并发版本
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 * @param name 名称
 * @param modelId 所属模型
 * @param maskedSuffix 脱敏尾部；短密钥不暴露任何字符
 * @param status 启停状态
 * @param rotatedAt 最后轮换时间 */
 public record View(UUID id,long version,OffsetDateTime createdAt,OffsetDateTime updatedAt,String name,UUID modelId,String maskedSuffix,ModelDtos.Status status,OffsetDateTime rotatedAt) {}
}
