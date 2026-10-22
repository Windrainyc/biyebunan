package cn.zhixiang.platform.identity;
import cn.zhixiang.platform.shared.BusinessException;
import java.time.*;
import java.util.*;
import java.security.*;
import org.springframework.stereotype.Service;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.transaction.support.TransactionTemplate;
/** 会话用例：密码计算在事务外，账户校验和轮换在短事务内，令牌只返回一次。 */
@Service
public class AuthService {
 private final TotpVerifier mfa;private final AuthMapper mapper;private final TokenDigester digester;private final TransactionTemplate tx;
 private final PasswordEncoder passwords=PasswordEncoderFactories.createDelegatingPasswordEncoder();
 private final String dummy=passwords.encode("dummy-never-a-real-user-password");
 private final SecureRandom random=new SecureRandom();
 /** @param mapper 认证数据库访问 @param digester 摘要 @param tx 短事务执行器 @param mfa 管理员二次认证 */
 public AuthService(AuthMapper mapper,TokenDigester digester,TransactionTemplate tx,TotpVerifier mfa){this.mfa=mfa;this.mapper=mapper;this.digester=digester;this.tx=tx;}
 /** @param platform 路径平台 @param input 登录请求 @param ip 直连来源，不信任任意转发头 @return 会话与刷新值 */
 public AuthDtos.Issued login(String platform,AuthDtos.Login input,String ip) {
  platform(platform);
  // 限速在单独已提交语句中计数，密码错误不能回滚失败次数。
  if(mapper.attempt(digester.digest("limit-ip",ip))>100 || mapper.attempt(digester.digest("limit-account",platform+input.phone()))>10)
   throw new BusinessException(429,"RATE_LIMITED","登录尝试过多，请稍后重试");
  var account=mapper.find(platform,digester.digest("phone",input.phone()));
  boolean matches=passwords.matches(input.password(),account==null?dummy:account.passwordHash());
  if(account==null || !matches || !account.status().equals("ENABLED"))throw expired();

  return tx.execute(s->{
   var current=mapper.lockAccount(account.id());
   if(!current.status().equals("ENABLED") || !current.passwordHash().equals(account.passwordHash()))throw expired();
   if(platform.equals("admin")) {
    long step=mfa.verify(current.mfaSecretRef(),input.mfaCode());
    if(mapper.consumeMfa(current.id(),step)!=1)throw new BusinessException(401,"MFA_INVALID","二次验证码已使用");
   }
   return issue(current,UUID.randomUUID(),OffsetDateTime.now(ZoneOffset.UTC).plusDays(7));
  });
 }
 /** @param platform 路径平台 @param refresh HttpOnly Cookie @param csrf 双提交校验后的请求值 @return 轮换的新会话 */
 public AuthDtos.Issued refresh(String platform,String refresh,String csrf) {
  platform(platform);
  var outcome=tx.execute(s->{
   var old=locate(platform,refresh,csrf);
   // 旧刷新值再次使用表示重放，撤销必须先提交，再向 HTTP 层抛错误。
   if(old.consumedAt()!=null){mapper.revoke(old.familyId());return new Outcome(null,true);}
   if(old.revokedAt()!=null || old.refreshExpiresAt().isBefore(OffsetDateTime.now()))throw expired();
   var account=mapper.lockAccount(old.userId());
   if(!account.status().equals("ENABLED") || account.authVersion()!=old.authVersion())throw expired();
   mapper.consume(old.id());
   return new Outcome(issue(account,old.familyId(),old.refreshExpiresAt()),false);
  });
  if(outcome.replay())throw new BusinessException(401,"TOKEN_REPLAY","刷新令牌重复使用，请重新登录");
  return outcome.issued();
 }
 /** @param platform 路径平台 @param refresh 刷新 Cookie @param csrf CSRF值；登出整个令牌族 */
 public void logout(String platform,String refresh,String csrf) {
  platform(platform);tx.executeWithoutResult(s->{var old=locate(platform,refresh,csrf);mapper.revoke(old.familyId());});
 }
 /** @param actor 已验证身份 @return 当前用户 DTO */
 public AuthDtos.User me(Actor actor) {
  return tx.execute(s->view(mapper.lockAccount(actor.id())));
 }
 private AuthMapper.SessionRow locate(String platform,String refresh,String csrf) {
  if(refresh==null || csrf==null || refresh.length()>256 || csrf.length()>256)throw expired();
  var found=mapper.findRefresh(digester.digest("refresh",refresh));if(found==null)throw expired();
  var account=mapper.lockAccount(found.userId()); // 统一先锁账户再锁会话。
  var old=mapper.lockSession(found.id());
  if(!account.platform().equals(platform) || !MessageDigest.isEqual(old.csrfDigest().getBytes(java.nio.charset.StandardCharsets.UTF_8),digester.digest("csrf",csrf).getBytes(java.nio.charset.StandardCharsets.UTF_8)))throw expired();
  return old;
 }
 private AuthDtos.Issued issue(AuthMapper.Account a,UUID family,OffsetDateTime refreshExpiry) {
  String access=token(),refresh=token(),csrf=token();OffsetDateTime now=OffsetDateTime.now(ZoneOffset.UTC);
  mapper.insert(new AuthMapper.SessionRow(UUID.randomUUID(),a.id(),digester.digest("access",access),digester.digest("refresh",refresh),digester.digest("csrf",csrf),a.authVersion(),now.plusMinutes(15),refreshExpiry,family,null,null));
  return new AuthDtos.Issued(new AuthDtos.Session(access,900,a.platform(),view(a),csrf),refresh);
 }
 private AuthDtos.User view(AuthMapper.Account a) {return new AuthDtos.User(a.id(),a.version(),a.createdAt(),a.updatedAt(),a.displayName(),a.phoneMasked(),a.platform(),a.status(),a.schoolId(),a.majorId(),mapper.roles(a.id()));}
 private String token(){byte[] b=new byte[32];random.nextBytes(b);return Base64.getUrlEncoder().withoutPadding().encodeToString(b);}
 private static BusinessException expired(){return new BusinessException(401,"SESSION_EXPIRED","登录凭证无效或会话已失效");}
 private static void platform(String p){if(!Set.of("student","partner","admin").contains(p))throw new BusinessException(404,"RESOURCE_NOT_FOUND","入口不存在");}
 private record Outcome(AuthDtos.Issued issued,boolean replay){}
}
