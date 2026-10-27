package cn.zhixiang.platform.identity.service;

import cn.zhixiang.platform.shared.security.SecretCipher;

import org.apache.ibatis.annotations.*;
import org.springframework.stereotype.Service;

import java.util.UUID;

/** 加密密钥仓库，业务表只保存UUID引用。 */
@Service
public class SecretVault {
    private final Store store;
    private final SecretCipher cipher;

    /**
     * 初始化模块组件，所需端口和配置由构造器注入。
     *
     * @param store 密文端口
     * @param cipher 加密服务
     */
    public SecretVault(Store store, SecretCipher cipher) {
        this.store = store;
        this.cipher = cipher;
    }

    /**
     * 返回随机引用。
     *
     * @param plain Base32种子等机密
     * @return 随机引用
     */
    public UUID put(String plain) {
        UUID id = UUID.randomUUID();
        store.insert(id, cipher.encrypt("secret:" + id, plain));
        return id;
    }

    /**
     * 返回解密秘密，不存在时null。
     *
     * @param id 引用
     * @return 解密秘密，不存在时null
     */
    public String get(UUID id) {
        String v = store.get(id);
        return v == null ? null : cipher.decrypt("secret:" + id, v);
    }

    /** 仅访问密文表。 */
    @Mapper
    public interface Store {
        /**
         * 在调用方业务事务内插入记录，由数据库约束保证唯一性和引用完整性。
         *
         * @param id 引用
         * @param ciphertext 加密数据
         */
        @Insert("INSERT INTO secret_material(id,ciphertext) VALUES(#{id},#{ciphertext})")
        void insert(UUID id, String ciphertext);

        /**
         * 返回密文。
         *
         * @param id 引用
         * @return 密文
         */
        @Select("SELECT ciphertext FROM secret_material WHERE id=#{id}")
        String get(UUID id);
    }
}
