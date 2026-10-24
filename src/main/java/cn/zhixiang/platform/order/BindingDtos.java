package cn.zhixiang.platform.order;
import java.util.UUID;
import jakarta.validation.constraints.*;
/** 订单一次性归属材料。 */
public final class BindingDtos {
 private BindingDtos() {}
 /** 请求内不允许指定目标学生。
 * @param orderNo 业务订单号
 * @param challengeId 指定订单与当前学生的挑战
 * @param code 一次性短信码 */
 public record Write(@NotBlank @Size(max=200) String orderNo,@NotNull UUID challengeId,@NotNull @Pattern(regexp="[0-9]{6}") String code) {
 /** 日志脱敏。
 * @return 不含验证码的文本 */
 @Override public String toString(){return "OrderBinding[redacted]";}
 }
}
