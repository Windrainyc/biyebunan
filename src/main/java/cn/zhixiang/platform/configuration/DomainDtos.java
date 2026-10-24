package cn.zhixiang.platform.configuration;
import java.util.UUID;
import java.time.OffsetDateTime;
import jakarta.validation.constraints.*;
/** 配置请求和响应；保存元数据不会自动修改网络、桶权限或部署证书。 */
public final class DomainDtos {
 private DomainDtos() {}
 /** 配置启停状态。 */
 public enum Status { /** 可选择。 */ ENABLED, /** 停用保留历史。 */ DISABLED }
 /** 域名所属平台。 */
 public enum Platform { /** 学生端。 */ student, /** 中介端。 */ partner, /** 管理端。 */ admin, /** 资源域。 */ assets }
 /** 配置写入。
 * @param name 配置展示名称
 * @param hostname 域名，按小写保存，不含协议端口或路径
 * @param platform 服务平台
 * @param certificateRef 部署层证书别名，不接收私钥或任意路径
 * @param status 是否启用配置；不代表 DNS 和 TLS 已部署
 */
 public record Write(@NotBlank @Size(max=200) String name,@NotBlank @Size(max=253) String hostname,@NotNull Platform platform,@NotBlank @Pattern(regexp="[A-Za-z0-9][A-Za-z0-9._-]{0,199}") String certificateRef,@NotNull Status status) {}
 /** 启停请求。
 * @param status 目标状态 */
 public record StatusWrite(@NotNull Status status) {}
 /** 只读配置响应。
 * @param id 唯一标识
 * @param version CAS版本
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 * @param name 配置展示名称
 * @param hostname 域名，按小写保存，不含协议端口或路径
 * @param platform 服务平台
 * @param certificateRef 部署层证书别名，不接收私钥或任意路径
 * @param status 是否启用配置；不代表 DNS 和 TLS 已部署
 */
 public record View(UUID id,long version,OffsetDateTime createdAt,OffsetDateTime updatedAt,String name,String hostname,Platform platform,String certificateRef,Status status) {}
}
