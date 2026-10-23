package cn.zhixiang.platform.identity;
import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;
import java.util.UUID;
/** 验证码和注册请求；所有验证码与密码均只写，不进入响应。 */
public final class VerificationDtos {
 private VerificationDtos() {}
 /** 用途严格隔离，注册验证码不能作为订单归属凭证。 */
 public enum Purpose { REGISTER, RESET_PASSWORD, ACTIVATE, BIND_ORDER }
 /**
 * @param phone 手机号
 * @param purpose 用途
 * @param orderNo 订单绑定时使用
 * @param platform 密码重置的目标平台，默认student，不能赋予角色 */
 public record ChallengeRequest(@NotNull @Pattern(regexp="^1\\d{10}$") String phone,@NotNull Purpose purpose,@Size(max=200) String orderNo,@Pattern(regexp="student|partner|admin") String platform) {}
 /**
 * @param challengeId 挑战标识
 * @param expiresAt 截止时间
 * @param retryAfterSeconds 发码冷却秒数 */
 public record Challenge(UUID challengeId,OffsetDateTime expiresAt,int retryAfterSeconds) {}
 /**
 * @param phone 手机号
 * @param password 新密码
 * @param displayName 昵称
 * @param challengeId REGISTER挑战
  *
 * @param code 验证码
 * @param termsVersion 当前明确同意的条款版本 */
 public record Register(@NotNull @Pattern(regexp="^1\\d{10}$") String phone,@NotNull @Size(min=10,max=128) String password,
   @NotBlank @Size(max=200) String displayName,@NotNull UUID challengeId,@NotNull @Pattern(regexp="^\\d{6}$") String code,@NotBlank @Size(max=200) String termsVersion) {}
 /**
 * @param challengeId RESET_PASSWORD挑战
 * @param code 验证码
 * @param newPassword 新密码 */
 public record PasswordReset(@NotNull UUID challengeId,@NotNull @Pattern(regexp="^\\d{6}$") String code,@NotNull @Size(min=10,max=128) String newPassword) {}
}
