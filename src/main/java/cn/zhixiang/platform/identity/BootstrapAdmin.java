package cn.zhixiang.platform.identity;
import cn.zhixiang.platform.shared.*;
import org.springframework.boot.*;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.beans.factory.annotation.Value;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.time.OffsetDateTime;
/** 离线受控初始化；仅bootstrap profile可运行，无HTTP接口、默认密码或自动启用账户。 */
@Component
@Profile("bootstrap")
public class BootstrapAdmin implements ApplicationRunner {
 private final RoleMapper roles;private final UserMapper users;private final InvitationMapper invites;private final Passwords passwords;private final TokenDigester digester;private final WriteMapper audit;private final TransactionTemplate tx;private final ConfigurableApplicationContext context;private final JsonMapper json;private final String phone,name,origin;
 /** @param roles IAM互斥锁
  * @param users 账户端口
  * @param invites 邀请端口
  * @param passwords 不可登录初始密码
  * @param digester 摘要
  * @param audit 审计
  * @param tx 事务
  * @param context 启动后退出容器
  * @param json 安全文件序列化
  * @param phone 操作者显式指定的手机号
  * @param name 管理员名称
  * @param origin 管理员前端来源 */
 public BootstrapAdmin(RoleMapper roles,UserMapper users,InvitationMapper invites,Passwords passwords,TokenDigester digester,WriteMapper audit,TransactionTemplate tx,ConfigurableApplicationContext context,JsonMapper json,@Value("${ZX_BOOTSTRAP_PHONE:}") String phone,@Value("${ZX_BOOTSTRAP_NAME:}") String name,@Value("${ZX_ADMIN_ORIGIN:https://admin.invalid}") String origin){this.roles=roles;this.users=users;this.invites=invites;this.passwords=passwords;this.digester=digester;this.audit=audit;this.tx=tx;this.context=context;this.json=json;this.phone=phone;this.name=name;this.origin=origin;}
 /** @param args 不接受敏感命令行参数
  * @throws Exception 配置无效、已有管理员、文件存在或数据库失败 */
 @Override public void run(ApplicationArguments args) throws Exception {
  try {
   if(!phone.matches("1[0-9]{10}")||name.isBlank()||name.length()>200)throw new IllegalArgumentException("必须通过环境提供ZX_BOOTSTRAP_PHONE和ZX_BOOTSTRAP_NAME");
   Path directory=Path.of("secrets");Files.createDirectories(directory);Files.setPosixFilePermissions(directory,PosixFilePermissions.fromString("rwx------"));
   Path output=directory.resolve("bootstrap-invitation.json");
   // CREATE_NEW先占输出文件；不覆盖已有凭证。文件写成功后事务才提交；提交失败文件失效，不能用于登录。
   try(var writer=Files.newBufferedWriter(Files.createFile(output,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))))) {
    String hash=passwords.encode(UUID.randomUUID().toString()+UUID.randomUUID());
    tx.executeWithoutResult(s->{roles.lockIam();if(users.adminCount()!=0)throw new BusinessException(409,"BOOTSTRAP_ALREADY_DONE","已存在管理员，禁止重复初始化");
     UUID id=UUID.randomUUID(),invitation=UUID.randomUUID();UUID superRole=UUID.fromString("00000000-0000-4000-8000-000000000001");
     users.insert(id,new UserDtos.Create(phone,name,"admin",List.of(superRole)),digester.digest("phone",phone),phone.substring(0,3)+"****"+phone.substring(7),hash);users.addRole(id,superRole);
     byte[] bytes=new byte[32];new java.security.SecureRandom().nextBytes(bytes);String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);var expiry=OffsetDateTime.now().plusHours(24);
     invites.insert(invitation,id,digester.digest("invitation",token),expiry);audit.audit(UUID.randomUUID(),id,"BOOTSTRAP_CREATED","user",id);
     try{writer.write(json.writeValueAsString(new UserDtos.Invitation(invitation,expiry,origin+"/activate#token="+token)));writer.flush();}catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}
    });
   }
  } finally {context.close();}
 }
}
