package cn.zhixiang.platform.identity;
import java.util.UUID;
import cn.zhixiang.platform.shared.SecretCipher;
import org.apache.ibatis.annotations.*;
import org.springframework.stereotype.Service;
/** 加密密钥仓库，业务表只保存UUID引用。 */
@Service
public class SecretVault {
 private final Store store;private final SecretCipher cipher;
 /** @param store 密文端口
  * @param cipher 加密服务 */
 public SecretVault(Store store,SecretCipher cipher){this.store=store;this.cipher=cipher;}
 /** @param plain Base32种子等机密
  * @return 随机引用 */
 public UUID put(String plain){UUID id=UUID.randomUUID();store.insert(id,cipher.encrypt("secret:"+id,plain));return id;}
 /** @param id 引用
  * @return 解密秘密，不存在时null */
 public String get(UUID id){String v=store.get(id);return v==null?null:cipher.decrypt("secret:"+id,v);}
 /** 仅访问密文表。 */
 @Mapper public interface Store {
  /** @param id 引用
   * @param ciphertext 加密数据 */
  @Insert("INSERT INTO secret_material(id,ciphertext) VALUES(#{id},#{ciphertext})") void insert(UUID id,String ciphertext);
  /** @param id 引用
   * @return 密文 */
  @Select("SELECT ciphertext FROM secret_material WHERE id=#{id}") String get(UUID id);
 }
}
