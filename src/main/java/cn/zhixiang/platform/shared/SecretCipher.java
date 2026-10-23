package cn.zhixiang.platform.shared;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.security.SecureRandom;
import javax.crypto.*;
import javax.crypto.spec.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
/** AES-GCM封装：每次随机96位nonce，固定用途AAD，密钥派生与身份摘要用途隔离。 */
@Component
public class SecretCipher {
 private final byte[] key;private final SecureRandom random=new SecureRandom();
 /** @param master 部署级秘密，所有副本一致；轮换需迁移历史密文 */
 public SecretCipher(@Value("${ZX_AUTH_HMAC_KEY}") String master){
  if(master.length()<32)throw new IllegalArgumentException("密钥长度不足");
  try{Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(master.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));key=mac.doFinal("zhixiang/aes-gcm/v1".getBytes(StandardCharsets.UTF_8));}catch(Exception e){throw new IllegalStateException(e);}
 }
 /** @param purpose 固定用途/对象标识，防止密文跨对象替换
  * @param plain 明文
  * @return v1加密信封 */
 public String encrypt(String purpose,String plain){
  try{byte[] nonce=new byte[12];random.nextBytes(nonce);Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));c.updateAAD(purpose.getBytes(StandardCharsets.UTF_8));return "v1:"+Base64.getEncoder().encodeToString(nonce)+":"+Base64.getEncoder().encodeToString(c.doFinal(plain.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException("秘密加密失败",e);}
 }
 /** @param purpose 加密时的用途/对象标识
  * @param envelope 密文信封
  * @return 经认证的明文，不应写日志 */
 public String decrypt(String purpose,String envelope){
  try{String[] parts=envelope.split(":",3);if(parts.length!=3||!parts[0].equals("v1"))throw new IllegalArgumentException();Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,Base64.getDecoder().decode(parts[1])));c.updateAAD(purpose.getBytes(StandardCharsets.UTF_8));return new String(c.doFinal(Base64.getDecoder().decode(parts[2])),StandardCharsets.UTF_8);}catch(Exception e){throw new BusinessException(503,"DEPENDENCY_UNAVAILABLE","秘密数据无法解密，请检查部署密钥版本");}
 }
}
