package cn.zhixiang.platform.identity.adapter;

import cn.zhixiang.platform.identity.dto.VerificationDtos;
import cn.zhixiang.platform.identity.port.SmsGateway;
import cn.zhixiang.platform.shared.exception.BusinessException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

/** 可配置企业短信网关适配器；默认关闭，严格HTTPS、不跟随跳转、不记录短信内容。 */
@Component
public class HttpSmsGateway implements SmsGateway {
    private final String endpoint, key;
    private final boolean enabled;
    private final JsonMapper json;
    private final HttpClient client =
            HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(3))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();

    /**
     * 初始化模块组件，所需端口和配置由构造器注入。
     *
     * @param endpoint 已审定的固定网关地址
     * @param key 服务器密钥
     * @param enabled 是否明确启用
     * @param json 编码器
     */
    public HttpSmsGateway(
            @Value("${ZX_SMS_ENDPOINT:}") String endpoint,
            @Value("${ZX_SMS_KEY:}") String key,
            @Value("${ZX_SMS_ENABLED:false}") boolean enabled,
            JsonMapper json) {
        this.endpoint = endpoint;
        this.key = key;
        this.enabled = enabled;
        this.json = json;
    }

    /** 验证真实网关配置；不提供日志打印验证码的替代路径。 */
    @Override
    public void requireConfigured() {
        if (!enabled || key.isBlank() || !endpoint.startsWith("https://")) throw unavailable();
    }

    /**
     * 发送单次请求；202/200且accepted=true才表示供应商接收。
     *
     * @param id 幂等号
     * @param phone 手机号
     * @param code 验证码
     * @param purpose 用途
     */
    @Override
    public void send(UUID id, String phone, String code, VerificationDtos.Purpose purpose) {
        requireConfigured();
        try {
            String body =
                    json.writeValueAsString(
                            Map.of(
                                    "challengeId",
                                    id,
                                    "phone",
                                    phone,
                                    "code",
                                    code,
                                    "purpose",
                                    purpose.name()));
            var request =
                    HttpRequest.newBuilder(URI.create(endpoint))
                            .timeout(Duration.ofSeconds(8))
                            .header("Authorization", "Bearer " + key)
                            .header("Content-Type", "application/json")
                            .header("Idempotency-Key", id.toString())
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if ((response.statusCode() != 200 && response.statusCode() != 202)
                    || !json.readTree(response.body()).path("accepted").asBoolean(false))
                throw unavailable();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (Exception e) {
            throw unavailable();
        }
    }

    private static BusinessException unavailable() {
        return new BusinessException(503, "PROVIDER_UNAVAILABLE", "短信服务未就绪或发送结果无法确认，请稍后重试");
    }
}
