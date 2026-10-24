package cn.zhixiang.platform.foundation;
import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;
import java.util.UUID;
/** 专业接口模型，输入不包含只读 ID、版本和审计字段。 */
public final class MajorDtos {
 private MajorDtos() {}
 /** 启停状态；停用保留历史引用。 */
 public enum Status { /** 可供新业务选择。 */ ENABLED, /** 停用并保留历史引用。 */ DISABLED }
 /** 学历层级。 */ public enum Level { /** 专科。 */ COLLEGE, /** 本科。 */ BACHELOR, /** 硕士。 */ MASTER }
 /** 已验证业务写入请求，字段不含服务端生成的标识或版本。
 * @param name 展示名称
 * @param code 稳定代码
 * @param schoolId 所属院校
 * @param level 学历
 * @param status 启停状态 */
 public record Write(@NotBlank @Size(max=200) String name,@NotBlank @Size(max=200) String code,@NotNull UUID schoolId, @NotNull Level level,@NotNull Status status) {}
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
 * @param schoolId 院校标识
 * @param level 学历
 * @param status 启停状态 */
 public record View(UUID id,long version,OffsetDateTime createdAt,OffsetDateTime updatedAt,String name,String code,UUID schoolId, Level level,Status status) {}
}
