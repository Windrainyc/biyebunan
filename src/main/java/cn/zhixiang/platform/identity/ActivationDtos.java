package cn.zhixiang.platform.identity;
import jakarta.validation.constraints.*;
import java.util.UUID;
import java.time.OffsetDateTime;
/** 邀请激活材料，令牌及MFA地址不得记录到日志。 */
public final class ActivationDtos {
 private ActivationDtos() {}
 /** @param expiresInHours 有效期小时数 */
 public record Reinvite(@Min(1) @Max(72) int expiresInHours) {}
 /** @param invitationToken 一次性令牌 */
 public record Enroll(@NotBlank @Size(max=200) String invitationToken) {}
 /** @param enrollmentId 登记标识
  * @param otpAuthUri 身份验证器配置URI，仅响应当前持令牌者
  * @param expiresAt 有效时间 */
 public record Enrollment(UUID enrollmentId,String otpAuthUri,OffsetDateTime expiresAt) {}
 /** @param invitationToken 邀请令牌
  * @param password 首次密码
  * @param displayName 昵称
  * @param termsVersion 条款版本
  * @param phone 预留手机号
  * @param challengeId 手机证明
  * @param code 短信验证码 */
 public record Partner(@NotBlank @Size(max=200) String invitationToken,@NotNull @Size(min=10,max=128) String password,@NotBlank @Size(max=200) String displayName,@NotBlank String termsVersion,@NotNull @Pattern(regexp="^1\\d{10}$") String phone,@NotNull UUID challengeId,@NotNull @Pattern(regexp="\\d{6}") String code) {}
 /** @param invitationToken 邀请令牌
  * @param password 首次密码
  * @param mfaEnrollmentId MFA登记
  * @param mfaCode 六位动态验证码 */
 public record Admin(@NotBlank @Size(max=200) String invitationToken,@NotNull @Size(min=10,max=128) String password,@NotNull UUID mfaEnrollmentId,@NotNull @Pattern(regexp="\\d{6}") String mfaCode) {}
}
