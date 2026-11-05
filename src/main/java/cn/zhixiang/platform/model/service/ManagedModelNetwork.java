package cn.zhixiang.platform.model.service;

import cn.zhixiang.platform.shared.exception.BusinessException;

import okhttp3.*;

import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

import java.util.*;
import java.util.concurrent.TimeUnit;

/** 固定官方只读模型目录检查，不调用生成接口，不跟随重定向。 */
@Component
public class ManagedModelNetwork {
    private final JsonMapper json;
    private final OkHttpClient http =
            new OkHttpClient.Builder()
                    .followRedirects(false)
                    .followSslRedirects(false)
                    .retryOnConnectionFailure(false)
                    .callTimeout(12, TimeUnit.SECONDS)
                    .build();

    /**
     * 官方服务配置操作，遵循版本与权限约束。
     *
     * @param json JSON解析器
     */
    public ManagedModelNetwork(JsonMapper json) {
        this.json = json;
    }

    /**
     * 官方服务配置操作，遵循版本与权限约束。
     *
     * @param secret 只用于官方认证，禁止日志
     * @return 官方模型代码
     */
    public List<String> models(String secret) {
        try (var response =
                http.newCall(
                                new Request.Builder()
                                        .url("https://api.deepseek.com/models")
                                        .header("Authorization", "Bearer " + secret)
                                        .get()
                                        .build())
                        .execute()) {
            if (response.code() == 401 || response.code() == 403)
                throw new BusinessException(422, "MODEL_KEY_REJECTED", "密钥无效或无权访问官方模型目录");
            if (!response.isSuccessful() || response.body() == null)
                throw new BusinessException(503, "MODEL_CHECK_FAILED", "官方服务暂不可用，请稍后重试");
            byte[] bytes = response.body().byteStream().readNBytes(65537);
            if (bytes.length > 65536) throw new IllegalStateException();
            var data = json.readTree(bytes).get("data");
            if (data == null || !data.isArray()) throw new IllegalStateException();
            var out = new ArrayList<String>();
            for (var v : data) {
                var id = v.get("id");
                if (id != null && id.isTextual()) out.add(id.asText());
            }
            return List.copyOf(out);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(503, "MODEL_CHECK_FAILED", "无法完成官方连接检查，请稍后重试");
        }
    }
}
