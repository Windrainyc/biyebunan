package cn.zhixiang.platform.identity;
import cn.zhixiang.platform.shared.*;
import java.time.*;
import java.util.*;
import java.security.*;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.support.TransactionTemplate;
/** 手机证明与注册/重置用例；失败次数提交后再抛错，验证码消费和账户修改同事务。 */
@Service
public class VerificationService {
 private final VerificationMapper mapper;private final AuthMapper accounts;private final AuthService auth;
 private final TokenDigester digester;private final Passwords passwords;private final SmsGateway sms;
 private final TransactionTemplate tx;private final WriteMapper writes;private final String terms;
 private final SecureRandom random=new SecureRandom();
 /**
 * @param mapper 挑战端口
 * @param accounts 账户端口
 * @param auth 会话签发
 * @param digester 摘要
  *
 * @param passwords 密码服务
 * @param sms 短信端口
 * @param tx 事务
 * @param writes 审计事件
 * @param terms 当前服务条款版本 */
 public VerificationService(VerificationMapper mapper,AuthMapper accounts,AuthService auth,TokenDigester digester,Passwords passwords,SmsGateway sms,TransactionTemplate tx,WriteMapper writes,@Value("${ZX_TERMS_VERSION:2026-10-01}") String terms){
  this.mapper=mapper;this.accounts=accounts;this.auth=auth;this.digester=digester;this.passwords=passwords;this.sms=sms;this.tx=tx;this.writes=writes;this.terms=terms;
 }
 /**
 * @param input 发送请求
 * @param ip 直连来源IP
 * @return 供应商确认接收的挑战，不返回验证码 */
 public VerificationDtos.Challenge challenge(VerificationDtos.ChallengeRequest input,String ip) {
  if(input.purpose()!=VerificationDtos.Purpose.REGISTER && input.purpose()!=VerificationDtos.Purpose.RESET_PASSWORD && input.purpose()!=VerificationDtos.Purpose.ACTIVATE)
   throw new BusinessException(503,"DEPENDENCY_UNAVAILABLE","该用途的验证流程尚未就绪");
  String platform=input.platform()==null?"student":input.platform();
  if(input.purpose()==VerificationDtos.Purpose.REGISTER && !platform.equals("student"))throw invalid();
  if(input.orderNo()!=null)throw new BusinessException(400,"VALIDATION_ERROR","当前验证用途不接受订单号");
  sms.requireConfigured();
  if(accounts.attempt(digester.digest("sms-ip",ip))>30 || accounts.attempt(digester.digest("sms-phone",input.phone()))>3)
   throw new BusinessException(429,"RATE_LIMITED","验证码发送次数过多，请十分钟后重试");
  UUID id=UUID.randomUUID();String code=String.format(Locale.ROOT,"%06d",random.nextInt(1000000));
  var expiry=OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(5);
  mapper.insert(id,digester.digest("phone",input.phone()),input.purpose().name(),platform,digester.digest("challenge:"+id,code),expiry);
  try {sms.send(id,input.phone(),code,input.purpose());mapper.sent(id,"READY");}
  catch(RuntimeException e){mapper.sent(id,"FAILED");throw e;}
  return new VerificationDtos.Challenge(id,expiry,600);
 }
 /**
 * @param ip 来源IP
 * @param input 注册材料；挑战必须属于此手机
 * @return 新学生会话 */
 public AuthDtos.Issued register(VerificationDtos.Register input,String ip) {
  rateLimit("register",ip);
  if(!terms.equals(input.termsVersion()))throw new BusinessException(422,"TERMS_VERSION_REQUIRED","请阅读并同意当前服务条款");
  String hash=passwords.encode(input.password()),phone=digester.digest("phone",input.phone());
  var result=tx.execute(s->{
   var row=mapper.lock(input.challengeId());
   if(!valid(row,"REGISTER","student",phone,input.code()))return null;
   UUID id=UUID.randomUUID();mapper.student(id,input.displayName().trim(),phone,input.phone().substring(0,3)+"****"+input.phone().substring(7),hash,terms);
   mapper.studentRole(id);mapper.consume(row.id());writes.audit(UUID.randomUUID(),id,"REGISTERED","user",id);
   writes.event(UUID.randomUUID(),id,"user.REGISTERED","{\"id\":\""+id+"\"}");
   return auth.issueVerified(accounts.lockAccount(id));
  });
  if(result==null)throw invalid();return result;
 }
 /**
 * @param ip 来源IP
 * @param input 密码重置材料；任何平台只重置密码，不移除管理员MFA */
 public void reset(VerificationDtos.PasswordReset input,String ip) {
  rateLimit("reset",ip);
  String hash=passwords.encode(input.newPassword());
  boolean done=Boolean.TRUE.equals(tx.execute(s->{
   var found=mapper.find(input.challengeId());
   if(found==null)return false;
   // 先账户后挑战；与登录/刷新及后续管理员禁用保持一致锁顺序。
   var account=accounts.find(found.platform(),found.phoneDigest());
   if(account!=null)account=accounts.lockAccount(account.id());
   var row=mapper.lock(found.id());
   if(!valid(row,"RESET_PASSWORD",found.platform(),found.phoneDigest(),input.code()))return false;
   mapper.consume(row.id());
   if(account!=null && account.status().equals("ENABLED")){
    mapper.reset(account.id(),hash);writes.audit(UUID.randomUUID(),account.id(),"PASSWORD_RESET","user",account.id());
    writes.event(UUID.randomUUID(),account.id(),"user.PASSWORD_RESET","{\"id\":\""+account.id()+"\"}");
   }
   return true; // 手机证明成功但账户不存在时不枚举账户存在性。
  }));
  if(!done)throw invalid();
 }
 private void rateLimit(String action,String ip) {
  if(accounts.attempt(digester.digest(action+"-ip",ip))>40)throw new BusinessException(429,"RATE_LIMITED","验证尝试过多，请十分钟后重试");
 }
 boolean valid(VerificationMapper.Row row,String purpose,String platform,String phone,String code) {
  if(row==null || !row.state().equals("READY") || row.attempts()>=5 || !row.expiresAt().isAfter(OffsetDateTime.now()) || !row.purpose().equals(purpose) || !row.platform().equals(platform) || !row.phoneDigest().equals(phone))return false;
  boolean matches=MessageDigest.isEqual(row.codeDigest().getBytes(java.nio.charset.StandardCharsets.US_ASCII),digester.digest("challenge:"+row.id(),code).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
  if(!matches)mapper.failedAttempt(row.id());return matches;
 }
 private static BusinessException invalid(){return new BusinessException(401,"VERIFICATION_INVALID","验证材料无效、已使用或已过期");}
}
