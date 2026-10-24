package cn.zhixiang.platform.identity;
import cn.zhixiang.platform.shared.BusinessException;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.*;
/** 三端认证入口；禁止请求体指定角色。Cookie 无 Domain，按平台和路径隔离。 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
 private final VerificationService verification;private final AuthService service; private final Map<String,String> origins;
 /** 初始化模块组件，所需端口和配置由构造器注入。
 * @param verification 手机验证用例
 * @param service 会话用例
 * @param student 学生允许来源
 * @param partner 中介来源
 * @param admin 管理来源 */
 public AuthController(VerificationService verification,AuthService service,@Value("${ZX_STUDENT_ORIGIN:https://student.invalid}") String student,@Value("${ZX_PARTNER_ORIGIN:https://partner.invalid}") String partner,@Value("${ZX_ADMIN_ORIGIN:https://admin.invalid}") String admin) {
  this.verification=verification;this.service=service;this.origins=Map.of("student",student,"partner",partner,"admin",admin);
 }
 /** 返回会话及安全 Cookie。
 * @param platform 路径平台
 * @param input 登录请求
 * @param request HTTP来源
 * @return 会话及安全 Cookie */
 @PostMapping("/{platform:student|partner|admin}/login")
 public ResponseEntity<AuthDtos.Session> login(@PathVariable String platform,@Valid @RequestBody AuthDtos.Login input,HttpServletRequest request) {
  return issued(platform,service.login(platform,input,request.getRemoteAddr()));
 }
 /** 返回轮换会话。
 * @param platform 路径平台
 * @param request Cookie与CSRF
 * @return 轮换会话 */
 @PostMapping("/{platform:student|partner|admin}/refresh")
 public ResponseEntity<AuthDtos.Session> refresh(@PathVariable String platform,HttpServletRequest request) {
  String csrf=verifyCsrf(platform,request);return issued(platform,service.refresh(platform,cookie(request,"zx_"+platform+"_refresh"),csrf));
 }
 /** 返回204并清除Cookie。
 * @param platform 路径平台
 * @param request Cookie与CSRF
 * @return 204并清除Cookie */
 @PostMapping("/{platform:student|partner|admin}/logout")
 public ResponseEntity<Void> logout(@PathVariable String platform,HttpServletRequest request) {
  String csrf=verifyCsrf(platform,request);service.logout(platform,cookie(request,"zx_"+platform+"_refresh"),csrf);
  return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE,refreshCookie(platform,"",0),csrfCookie(platform,"",0)).build();
 }
 /** 返回202挑战，短信失败返回503。
 * @param input 验证材料
 * @param request 请求来源
 * @return 202挑战，短信失败返回503 */
 @PostMapping("/challenges")
 public ResponseEntity<VerificationDtos.Challenge> challenge(@Valid @RequestBody VerificationDtos.ChallengeRequest input,HttpServletRequest request) {
  return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(verification.challenge(input,request.getRemoteAddr()));
 }
 /** 返回初始会话，不接受客户端角色。
 * @param input 学生注册材料
 * @param request 来源信息
 * @return 初始会话，不接受客户端角色 */
 @PostMapping("/student/register")
 public ResponseEntity<AuthDtos.Session> register(@Valid @RequestBody VerificationDtos.Register input,HttpServletRequest request) {var result=issued("student",verification.register(input,request.getRemoteAddr()));return ResponseEntity.status(201).headers(result.getHeaders()).body(result.getBody());}
 /** 返回204，不枚举账户。
 * @param input 密码重置材料
 * @param request 来源信息
 * @return 204，不枚举账户 */
 @PostMapping("/password-reset")
 public ResponseEntity<Void> reset(@Valid @RequestBody VerificationDtos.PasswordReset input,HttpServletRequest request) {verification.reset(input,request.getRemoteAddr());return ResponseEntity.noContent().build();}
 /** 返回脱敏用户。
 * @param actor 权威身份
 * @return 脱敏用户 */
 @GetMapping("/me")
 public AuthDtos.User me(@AuthenticationPrincipal Actor actor){return service.me(actor);}
 private String verifyCsrf(String platform,HttpServletRequest req) {
  String header=req.getHeader("X-CSRF-Token"),origin=req.getHeader("Origin");
  if(!origins.get(platform).equals(origin) || header==null || !header.equals(cookie(req,"zx_"+platform+"_csrf")))
   throw new BusinessException(403,"PERMISSION_DENIED","来源或 CSRF 校验失败");
  return header;
 }
 ResponseEntity<AuthDtos.Session> issued(String p,AuthDtos.Issued i){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header(HttpHeaders.SET_COOKIE,refreshCookie(p,i.refreshToken(),604800),csrfCookie(p,i.session().csrfToken(),604800)).body(i.session());}
 private String refreshCookie(String p,String v,long age){return ResponseCookie.from("zx_"+p+"_refresh",v).httpOnly(true).secure(true).sameSite("Lax").path("/api/v1/auth/"+p).maxAge(age).build().toString();}
 private String csrfCookie(String p,String v,long age){return ResponseCookie.from("zx_"+p+"_csrf",v).httpOnly(false).secure(true).sameSite("Lax").path("/").maxAge(age).build().toString();}
 private String cookie(HttpServletRequest req,String name){if(req.getCookies()==null)return null;return Arrays.stream(req.getCookies()).filter(c->c.getName().equals(name)).map(Cookie::getValue).findFirst().orElse(null);}
}
