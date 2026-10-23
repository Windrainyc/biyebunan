package cn.zhixiang.platform.identity;
import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;
import java.util.*;
/** 角色传输类型；内置标识不能由请求体控制。 */
public final class RoleDtos {
 private RoleDtos() {}
 /** 角色写入参数。
  * @param name 名称
  * @param code 全局唯一代码
  * @param platform 固定端别
  * @param dataScope 与平台匹配的数据范围
  * @param permissionCodes 白名单权限，不能授予操作者没有的权限
  * @param status 启停状态
  */
 public record Write(@NotBlank @Size(max=200) String name,@NotBlank @Pattern(regexp="[A-Z][A-Z0-9_]{1,63}") String code,
  @NotNull @Pattern(regexp="student|partner|admin") String platform,@NotNull @Pattern(regexp="SELF|OWNED|ALL") String dataScope,
  @NotNull @Size(max=300) List<@NotBlank @Size(max=200) String> permissionCodes,@NotNull @Pattern(regexp="ENABLED|DISABLED") String status) {}
 /** 角色只读响应。
  * @param id 标识
  * @param version 并发版本
  * @param createdAt 创建时间
  * @param updatedAt 更新时间
  * @param name 名称
  * @param code 唯一代码
  * @param platform 平台
  * @param dataScope 数据范围
  * @param permissionCodes 权限列表
  * @param status 状态
  */
 public record View(UUID id,long version,OffsetDateTime createdAt,OffsetDateTime updatedAt,String name,String code,String platform,String dataScope,List<String> permissionCodes,String status) {}
 /** @param status 目标启停状态 */
 public record State(@NotNull @Pattern(regexp="ENABLED|DISABLED") String status) {}
 /** @param code 权限代码
  * @param module 模块名称
  * @param description 权限含义 */
 public record Permission(String code,String module,String description) {}
}
