package cn.zhixiang.platform.identity;
import jakarta.validation.constraints.*;
import java.util.*;
import java.time.OffsetDateTime;
/** 管理员账户请求，不提供任意密码或访问令牌字段。 */
public final class UserDtos {
 private UserDtos() {}
 /** @param phone 预留手机号
  * @param displayName 昵称
  * @param platform 固定平台
  * @param roleIds 角色列表 */
 public record Create(@NotNull @Pattern(regexp="^1\\d{10}$") String phone,@NotBlank @Size(max=200) String displayName,@NotNull @Pattern(regexp="student|partner|admin") String platform,@NotNull @Size(max=20) List<@NotNull UUID> roleIds) {}
 /** @param displayName 昵称
  * @param schoolId 院校，可省略
  * @param majorId 专业，可省略
  * @param roleIds 同平台角色
  * @param status 目标状态
  * @param reason 管理员变更理由，必填 */
 public record Write(@NotBlank @Size(max=200) String displayName,UUID schoolId,UUID majorId,@NotNull @Size(max=20) List<@NotNull UUID> roleIds,@NotNull @Pattern(regexp="ENABLED|DISABLED") String status,@NotBlank @Size(max=200) String reason) {}
 /** @param phone 中介预留手机
  * @param displayName 昵称
  * @param expiresInHours 有效小时数，1至72 */
 public record Invite(@NotNull @Pattern(regexp="^1\\d{10}$") String phone,@NotBlank @Size(max=200) String displayName,@Min(1) @Max(72) int expiresInHours) {}
 /** @param id 邀请标识
  * @param expiresAt 过期时间
  * @param activationUrl 含一次性凭证的链接，禁止日志记录 */
 public record Invitation(UUID id,OffsetDateTime expiresAt,String activationUrl) {}
}
