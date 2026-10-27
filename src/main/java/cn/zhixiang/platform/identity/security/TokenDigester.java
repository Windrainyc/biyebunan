package cn.zhixiang.platform.identity.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** 域分离 HMAC；数据库泄露时不能直接离线枚举手机号或重用令牌摘要。 */
@Component
public class TokenDigester {
    private final byte[] key;

    /**
     * 初始化模块组件，所需端口和配置由构造器注入。
     *
     * @param secret 至少 32 字节的部署级秘密；所有副本必须一致
     */
    public TokenDigester(@Value("${ZX_AUTH_HMAC_KEY}") String secret) {
        key = secret.getBytes(StandardCharsets.UTF_8);
        if (key.length < 32) throw new IllegalArgumentException("认证 HMAC 密钥长度不足");
    }

    /**
     * 返回十六进制摘要。
     *
     * @param domain access、refresh、phone 等用途
     * @param value 原值
     * @return 十六进制摘要
     */
    public String digest(String domain, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of()
                    .formatHex(
                            mac.doFinal((domain + ":" + value).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("HMAC 不可用", e);
        }
    }
}
