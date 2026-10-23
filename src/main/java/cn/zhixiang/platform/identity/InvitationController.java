package cn.zhixiang.platform.identity;
import cn.zhixiang.platform.shared.*;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.*;
/** 邀请签发与公开激活入口；公开端点仅使用一次性凭证，不能切换平台。 */
@RestController
@RequestMapping("/api/v1")
public class InvitationController {
 private final InvitationService service;private final IdempotentWrites writes;private final AuthController auth;
 /** @param service 激活用例
  * @param writes 写入幂等
  * @param auth 统一安全Cookie响应 */
 public InvitationController(InvitationService service,IdempotentWrites writes,AuthController auth){this.service=service;this.writes=writes;this.auth=auth;}
 /** @param input 中介资料
  * @param key 幂等键
  * @param actor 当前管理员
  * @return 邀请链接，重复请求解密同一响应 */
 @PostMapping("/admin/partner-invitations") @PreAuthorize("hasAuthority('post_admin_partner_invitations')")
 public ResponseEntity<String> partner(@Valid @RequestBody UserDtos.Invite input,@RequestHeader("Idempotency-Key") String key,@AuthenticationPrincipal Actor actor){return writes.execute(actor.id(),"POST","/api/v1/admin/partner-invitations",key,input,()->ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(service.partner(input,actor.id())));}
 /** @param id 待激活账户
  * @param input 有效时间
  * @param key 幂等键
  * @param actor 管理员
  * @return 一次性邀请链接 */
 @PostMapping("/admin/users/{id}/invitations") @PreAuthorize("hasAuthority('post_admin_users')")
 public ResponseEntity<String> invite(@PathVariable UUID id,@Valid @RequestBody ActivationDtos.Reinvite input,@RequestHeader("Idempotency-Key") String key,@AuthenticationPrincipal Actor actor){return writes.execute(actor.id(),"POST","/api/v1/admin/users/"+id+"/invitations",key,input,()->ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(service.invite(id,input.expiresInHours(),actor.id())));}
 /** @param input 邀请
  * @param request 来源
  * @return 登记信息，禁止缓存 */
 @PostMapping("/auth/admin/mfa-enrollments")
 public ResponseEntity<ActivationDtos.Enrollment> enroll(@Valid @RequestBody ActivationDtos.Enroll input,HttpServletRequest request){return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(service.enroll(input,request.getRemoteAddr()));}
 /** @param input 中介激活证明
  * @param request 来源
  * @return 中介会话 */
 @PostMapping("/auth/partner/activate")
 public ResponseEntity<AuthDtos.Session> partnerActivate(@Valid @RequestBody ActivationDtos.Partner input,HttpServletRequest request){var r=auth.issued("partner",service.activatePartner(input,request.getRemoteAddr()));return ResponseEntity.status(201).headers(r.getHeaders()).body(r.getBody());}
 /** @param input MFA激活证明
  * @param request 来源
  * @return 管理员会话 */
 @PostMapping("/auth/admin/activate")
 public ResponseEntity<AuthDtos.Session> adminActivate(@Valid @RequestBody ActivationDtos.Admin input,HttpServletRequest request){var r=auth.issued("admin",service.activateAdmin(input,request.getRemoteAddr()));return ResponseEntity.status(201).headers(r.getHeaders()).body(r.getBody());}
}
