package cn.zhixiang.platform.callback.security;

import cn.zhixiang.platform.shared.exception.BusinessException;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/** OSS回调原文验签；公钥从部署可信文件加载，绝不请求回调提供的任意URL。 */
@Component
public class OssCallbackSignature {
    private final PublicKey key;
    private final String keyUrl;

    /**
     * 加载受保护部署指定的官方公钥；未配置时回调显式不可用。
     *
     * @param environment 公钥文件和官方来源配置
     */
    @Autowired
    public OssCallbackSignature(Environment environment) {
        keyUrl =
                environment.getProperty(
                        "ZX_OSS_CALLBACK_KEY_URL",
                        "https://gosspublic.alicdn.com/callback_pub_key_v1.pem");
        String file = environment.getProperty("ZX_OSS_CALLBACK_PUBLIC_KEY_FILE", "");
        if (file.isBlank()) {
            key = null;
            return;
        }
        try {
            if (!keyUrl.matches(
                    "https://gosspublic\\.alicdn\\.com/callback_pub_key_[A-Za-z0-9_-]+\\.pem")) {
                throw new IllegalArgumentException("公钥来源不合法");
            }
            String pem = Files.readString(Path.of(file), StandardCharsets.US_ASCII);
            if (pem.length() > 16384) throw new IllegalArgumentException("公钥文件过大");
            byte[] encoded =
                    Base64.getDecoder()
                            .decode(
                                    pem.replace("-----BEGIN PUBLIC KEY-----", "")
                                            .replace("-----END PUBLIC KEY-----", "")
                                            .replaceAll("\\s", ""));
            key = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(encoded));
        } catch (Exception exception) {
            throw new IllegalStateException("OSS回调公钥配置无效", exception);
        }
    }

    /**
     * 供隔离签名协议测试注入独立RSA公钥，不修改生产公钥信任来源。
     *
     * @param key 测试公钥
     * @param keyUrl 固定来源
     */
    public OssCallbackSignature(PublicKey key, String keyUrl) {
        this.key = key;
        this.keyUrl = keyUrl;
    }

    /**
     * 按OSS规定的MD5withRSA验证路径、原始查询串和原始消息字节。
     *
     * @param path 已解码固定回调路径
     * @param query 原始查询串，不含问号，可空
     * @param body 原始请求体，最多16KiB
     * @param authorization Base64签名，非Bearer令牌
     * @param encodedKeyUrl Base64官方来源，必须与部署固定来源完全一致
     * @throws BusinessException 未配置或签名不可信
     */
    public void verify(
            String path, String query, byte[] body, String authorization, String encodedKeyUrl) {
        if (key == null)
            throw new BusinessException(503, "OSS_CALLBACK_UNCONFIGURED", "尚未配置OSS回调公钥");
        try {
            if (body.length > 16384
                    || authorization == null
                    || authorization.length() > 4096
                    || encodedKeyUrl == null
                    || encodedKeyUrl.length() > 2048) throw new IllegalArgumentException();
            String source =
                    new String(
                            Base64.getDecoder().decode(encodedKeyUrl), StandardCharsets.US_ASCII);
            if (!source.equals(keyUrl)) throw new IllegalArgumentException();
            Signature verifier = Signature.getInstance("MD5withRSA");
            verifier.initVerify(key);
            verifier.update(
                    (path + (query == null ? "" : "?" + query) + "\n")
                            .getBytes(StandardCharsets.UTF_8));
            verifier.update(body);
            if (!verifier.verify(Base64.getDecoder().decode(authorization)))
                throw new IllegalArgumentException();
        } catch (Exception exception) {
            throw new BusinessException(401, "OSS_SIGNATURE_INVALID", "OSS回调签名无效");
        }
    }
}
