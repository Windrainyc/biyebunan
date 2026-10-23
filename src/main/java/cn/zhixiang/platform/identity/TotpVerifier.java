package cn.zhixiang.platform.identity;
import cn.zhixiang.platform.shared.BusinessException;
import java.nio.file.*;
import java.nio.ByteBuffer;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
/** RFC 6238 六位 TOTP 校验；仅从受控目录读取 UUID 引用，数据库不存明文种子。 */
@Component
public class TotpVerifier {
 private final Path root;
 /**
 * @param directory 受保护密钥目录；生产由 secret volume 注入，不接受用户路径 */
 public TotpVerifier(@Value("${ZX_MFA_SECRET_DIR:secrets/mfa}") String directory){root=Path.of(directory).toAbsolutePath().normalize();}
 /**
 * @param reference UUID 形式的密钥引用
 * @param code 六位验证码
 * @return 匹配时间步，后续数据库必须原子防重放 */
 public long verify(String reference,String code) {
  if(code==null)throw new BusinessException(401,"MFA_REQUIRED","管理员需要二次验证码");
  if(reference==null)throw new BusinessException(503,"DEPENDENCY_UNAVAILABLE","管理员 MFA 尚未配置");
  try {
   String name=UUID.fromString(reference).toString();Path path=root.resolve(name);
   if(Files.isSymbolicLink(path) || Files.size(path)>128)throw new java.io.IOException("invalid secret file");
   byte[] key=decode(Files.readString(path).strip());
   long current=java.time.Instant.now().getEpochSecond()/30;
   for(long step=current-1;step<=current+1;step++)
    if(java.security.MessageDigest.isEqual(generate(key,step).getBytes(java.nio.charset.StandardCharsets.US_ASCII),code.getBytes(java.nio.charset.StandardCharsets.US_ASCII)))return step;
  } catch(java.io.IOException|IllegalArgumentException e) {throw new BusinessException(503,"DEPENDENCY_UNAVAILABLE","MFA 密钥不可用");}
  throw new BusinessException(401,"MFA_INVALID","二次验证码无效");
 }
 /**
 * @param key 原始种子字节
 * @param step Unix秒数除以30
 * @return 六位动态密码 */
 public static String generate(byte[] key,long step) {
  try {
   Mac mac=Mac.getInstance("HmacSHA1");mac.init(new SecretKeySpec(key,"HmacSHA1"));
   byte[] h=mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());int offset=h[h.length-1]&15;
   int binary=((h[offset]&127)<<24)|((h[offset+1]&255)<<16)|((h[offset+2]&255)<<8)|(h[offset+3]&255);
   return String.format(Locale.ROOT,"%06d",binary%1000000);
  } catch(java.security.GeneralSecurityException e){throw new IllegalStateException("TOTP 算法不可用",e);}
 }
 private static byte[] decode(String base32) {
  if(!base32.matches("[A-Z2-7]{32,103}"))throw new IllegalArgumentException("invalid base32");
  var out=new java.io.ByteArrayOutputStream();int buffer=0,bits=0;
  for(char c:base32.toCharArray()){buffer=(buffer<<5)|"ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".indexOf(c);bits+=5;if(bits>=8){bits-=8;out.write((buffer>>bits)&255);}}
  return out.toByteArray();
 }
}
