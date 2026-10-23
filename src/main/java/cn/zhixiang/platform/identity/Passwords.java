package cn.zhixiang.platform.identity;
import java.util.Map;
import org.springframework.security.crypto.password.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
/** 新密码使用 PBKDF2，支持契约128字符且不受BCrypt的72字节截断限制；旧BCrypt可继续验证。 */
@Component
public class Passwords {
 private final PasswordEncoder encoder=new DelegatingPasswordEncoder("pbkdf2",Map.of("pbkdf2",Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8(),"bcrypt",new BCryptPasswordEncoder()));
 /**
 * @param raw 用户新密码
 * @return 带算法标识和随机盐的摘要；不得记录原文 */
 public String encode(String raw){return encoder.encode(raw);}
 /**
 * @param raw 用户输入
 * @param encoded 持久化摘要
 * @return 是否匹配，无效或旧格式拒绝 */
 public boolean matches(String raw,String encoded){try{return encoder.matches(raw,encoded);}catch(IllegalArgumentException e){return false;}}
}
