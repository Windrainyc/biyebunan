package cn.zhixiang.platform.callback;

import static org.junit.jupiter.api.Assertions.*;

import cn.zhixiang.platform.callback.security.OssCallbackSignature;
import cn.zhixiang.platform.shared.exception.BusinessException;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;

class OssCallbackSignatureTest {
    @Test
    void originalBytesAndPinnedKeySourceAreRequired() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var pair = generator.generateKeyPair();
        String source = "https://gosspublic.alicdn.com/callback_pub_key_v1.pem";
        var verifier = new OssCallbackSignature(pair.getPublic(), source);
        byte[] body = "bucket=test&object=a%2Fb&size=3".getBytes(StandardCharsets.UTF_8);
        var signer = Signature.getInstance("MD5withRSA");
        signer.initSign(pair.getPrivate());
        signer.update("/api/v1/callbacks/oss?x=1\n".getBytes(StandardCharsets.UTF_8));
        signer.update(body);
        String signature = Base64.getEncoder().encodeToString(signer.sign());
        String encoded =
                Base64.getEncoder().encodeToString(source.getBytes(StandardCharsets.US_ASCII));
        verifier.verify("/api/v1/callbacks/oss", "x=1", body, signature, encoded);
        assertEquals(
                "OSS_SIGNATURE_INVALID",
                assertThrows(
                                BusinessException.class,
                                () ->
                                        verifier.verify(
                                                "/api/v1/callbacks/oss",
                                                "x=2",
                                                body,
                                                signature,
                                                encoded))
                        .code());
        assertThrows(
                BusinessException.class,
                () ->
                        verifier.verify(
                                "/api/v1/callbacks/oss",
                                "x=1",
                                "tampered".getBytes(),
                                signature,
                                encoded));
        assertThrows(
                BusinessException.class,
                () ->
                        verifier.verify(
                                "/api/v1/callbacks/oss",
                                "x=1",
                                body,
                                signature,
                                Base64.getEncoder()
                                        .encodeToString("http://127.0.0.1/private".getBytes())));
        assertThrows(
                BusinessException.class,
                () ->
                        verifier.verify(
                                "/api/v1/callbacks/oss",
                                "x=1",
                                body,
                                "Bearer user-token",
                                encoded));
    }
}
