package cn.zhixiang.platform.membership;
import jakarta.validation.constraints.*;
import java.util.UUID;
import java.time.OffsetDateTime;
/** 会员接口模型；金额使用人民币元十进制字符串，词元为整数。 */
public final class MembershipDtos {
 private MembershipDtos() {}
 /** 模块数据记录，字段含义和取值约束见参数说明。
 * @param name 套餐名称
  * @param code 全局唯一编码
  * @param durationDays 有效天数1至36500
  * @param monthlyTokenQuota 每自然月词元上限，非负
  * @param monthlyAmountLimit 每月人民币额度，最多六位小数
  * @param status ENABLED或DISABLED */
 public record PlanWrite(@NotBlank @Size(max=200) String name,@NotBlank @Size(max=200) String code,@Min(1) @Max(36500) int durationDays,@NotNull @Min(0) Long monthlyTokenQuota,@NotNull @Pattern(regexp="[0-9]{1,14}(\\.[0-9]{1,6})?") String monthlyAmountLimit,@NotNull @Pattern(regexp="ENABLED|DISABLED") String status) {}
 /** 模块数据记录，字段含义和取值约束见参数说明。
 * @param durationDays 有效天数
  * @param monthlyTokenQuota 月词元上限
  * @param monthlyAmountLimit 月人民币上限
  * @param effectiveFrom 生效时刻，禁止回填已过去的版本 */
 public record VersionWrite(@Min(1) @Max(36500) int durationDays,@NotNull @Min(0) Long monthlyTokenQuota,@NotNull @Pattern(regexp="[0-9]{1,14}(\\.[0-9]{1,6})?") String monthlyAmountLimit,@NotNull OffsetDateTime effectiveFrom) {}
 /** 模块数据记录，字段含义和取值约束见参数说明。
 * @param id 标识
  * @param version 乐观锁版本
  * @param createdAt 创建时刻
  * @param updatedAt 更新时刻
  * @param name 名称
  * @param code 编码
  * @param durationDays 当前已生效权益天数
  * @param monthlyTokenQuota 当前月词元上限
  * @param monthlyAmountLimit 当前月人民币上限
  * @param status 启停状态 */
 public record Plan(UUID id,long version,OffsetDateTime createdAt,OffsetDateTime updatedAt,String name,String code,int durationDays,long monthlyTokenQuota,String monthlyAmountLimit,String status) {}
 /** 模块数据记录，字段含义和取值约束见参数说明。
 * @param id 不可变权益标识
  * @param version 套餐内递增权益版本号
  * @param createdAt 追加时刻
  * @param updatedAt 与创建时刻相同，不可变
  * @param durationDays 有效天数
  * @param monthlyTokenQuota 月词元上限
  * @param monthlyAmountLimit 月人民币上限
  * @param effectiveFrom 生效时刻
  * @param planId 套餐标识 */
 public record PlanVersion(UUID id,long version,OffsetDateTime createdAt,OffsetDateTime updatedAt,int durationDays,long monthlyTokenQuota,String monthlyAmountLimit,OffsetDateTime effectiveFrom,UUID planId) {}
 /** 模块数据记录，字段含义和取值约束见参数说明。
 * @param planId 套餐
  * @param startsAt 会员开始时刻
  * @param reason 发放原因 */
 public record Grant(@NotNull UUID planId,@NotNull OffsetDateTime startsAt,@NotBlank @Size(max=200) String reason) {}
 /** 模块数据记录，字段含义和取值约束见参数说明。
 * @param reason 撤销原因 */
 public record Reason(@NotBlank @Size(max=200) String reason) {}
 /** 模块数据记录，字段含义和取值约束见参数说明。
 * @param id 会员标识
  * @param version 乐观锁版本
  * @param createdAt 创建时刻
  * @param updatedAt 更新时间
  * @param userId 学生账户
  * @param planId 套餐
  * @param startsAt 起点，包含
  * @param expiresAt 终点，不包含
  * @param state SCHEDULED/ACTIVE/EXPIRED/REVOKED，按服务端时间投影 */
 public record Member(UUID id,long version,OffsetDateTime createdAt,OffsetDateTime updatedAt,UUID userId,UUID planId,OffsetDateTime startsAt,OffsetDateTime expiresAt,String state) {}
}
