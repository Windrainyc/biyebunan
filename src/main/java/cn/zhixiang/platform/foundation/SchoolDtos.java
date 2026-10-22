package cn.zhixiang.platform.foundation;
import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;
import java.util.UUID;
/** 院校接口模型，输入不包含只读 ID、版本和审计字段。 */
public final class SchoolDtos {
 private SchoolDtos() {}
 /** 启停状态；停用保留历史引用。 */
 public enum Status { ENABLED, DISABLED }
 
 /** @param name 展示名称 @param code 稳定代码 @param region 院校地区 @param status 启停状态 */
 public record Write(@NotBlank @Size(max=200) String name,@NotBlank @Size(max=200) String code,@NotBlank @Size(max=200) String region,@NotNull Status status) {}
 /** @param status 目标启停状态 */
 public record StatusWrite(@NotNull Status status) {}
 /** @param id 只读标识 @param version 乐观锁版本 @param createdAt UTC 创建时间 @param updatedAt UTC 更新时间
  * @param name 名称 @param code 代码 @param region 地区 @param status 启停状态 */
 public record View(UUID id,long version,OffsetDateTime createdAt,OffsetDateTime updatedAt,String name,String code,String region,Status status) {}
}
