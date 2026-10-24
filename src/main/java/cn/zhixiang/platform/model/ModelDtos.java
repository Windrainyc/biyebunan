package cn.zhixiang.platform.model;
import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;
import java.util.UUID;
/** 模型接口模型，输入不包含只读 ID、版本和审计字段。 */
public final class ModelDtos {
 private ModelDtos() {}
 /** 启停状态；停用保留历史引用。 */
 public enum Status { /** 可供新业务选择。 */ ENABLED, /** 停用并保留历史引用。 */ DISABLED }
 
 /** 已验证业务写入请求，字段不含服务端生成的标识或版本。
 * @param name 展示名称
 * @param code 稳定代码
 * @param provider 供应商适配器代码
 * @param endpoint HTTPS 服务端点，不允许包含凭证或查询参数
 * @param contextLimit 上下文词元上限
 * @param maxOutputTokens 单次输出词元上限，不得超过上下文
 * @param status 启停状态 */
 public record Write(@NotBlank @Size(max=200) String name,@NotBlank @Size(max=200) String code,@NotBlank @Size(max=200) String provider,@NotBlank @Size(max=2048) String endpoint,@NotNull @Min(1) Integer contextLimit,@NotNull @Min(1) Integer maxOutputTokens,@NotNull Status status) {}
 /** 资源启停请求。
 * @param status 目标启停状态 */
 public record StatusWrite(@NotNull Status status) {}
 /** 只读响应数据，版本及审计字段由服务端生成。
 * @param id 只读标识
 * @param version 乐观锁版本
 * @param createdAt UTC 创建时间
 * @param updatedAt UTC 更新时间
  *
 * @param name 名称
 * @param code 代码
 * @param provider 供应商适配器代码
 * @param endpoint HTTPS 服务端点，不允许包含凭证或查询参数
 * @param contextLimit 上下文词元上限
 * @param maxOutputTokens 单次输出词元上限，不得超过上下文
 * @param status 启停状态 */
 public record View(UUID id,long version,OffsetDateTime createdAt,OffsetDateTime updatedAt,String name,String code,String provider,String endpoint,int contextLimit,int maxOutputTokens,Status status) {}
}
