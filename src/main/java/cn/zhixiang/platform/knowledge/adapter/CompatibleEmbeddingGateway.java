package cn.zhixiang.platform.knowledge.adapter;

import cn.zhixiang.platform.knowledge.dto.EmbeddingResult;
import cn.zhixiang.platform.knowledge.port.EmbeddingGateway;
import cn.zhixiang.platform.shared.exception.BusinessException;

import okhttp3.*;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;

/** OpenAI兼容嵌入HTTP客户端；默认关闭、不重试、不跟随重定向、不输出文本或密钥。 */
@Component
public class CompatibleEmbeddingGateway implements EmbeddingGateway {
    private final boolean enabled;
    private final String endpoint, model, key, profile;
    private final int dimensions;
    private final JsonMapper json;
    private final OkHttpClient client =
            new OkHttpClient.Builder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .callTimeout(Duration.ofSeconds(30))
                    .followRedirects(false)
                    .followSslRedirects(false)
                    .retryOnConnectionFailure(false)
                    .build();

    /**
     * 读取部署固定配置；模型或维数变更产生新指纹，不混用不同向量空间。
     *
     * @param env ZX_EMBEDDING_ENABLED/ENDPOINT/MODEL/DIMENSIONS/API_KEY
     * @param json 协议编码器
     */
    public CompatibleEmbeddingGateway(Environment env, JsonMapper json) {
        this.json = json;
        enabled = env.getProperty("ZX_EMBEDDING_ENABLED", Boolean.class, false);
        endpoint = env.getProperty("ZX_EMBEDDING_ENDPOINT", "");
        model = env.getProperty("ZX_EMBEDDING_MODEL", "");
        dimensions = env.getProperty("ZX_EMBEDDING_DIMENSIONS", Integer.class, 1536);
        key = env.getProperty("ZX_EMBEDDING_API_KEY", "");
        if (dimensions < 1 || dimensions > 2000)
            throw new IllegalArgumentException("嵌入维数必须在1至2000之间");
        if (enabled) {
            URI uri = URI.create(endpoint);
            if (!Set.of("http", "https").contains(uri.getScheme())
                    || uri.getHost() == null
                    || uri.getUserInfo() != null
                    || uri.getQuery() != null
                    || uri.getFragment() != null
                    || model.isBlank()) throw new IllegalArgumentException("嵌入部署配置不完整");
        }
        try {
            profile =
                    HexFormat.of()
                            .formatHex(
                                    MessageDigest.getInstance("SHA-256")
                                            .digest(
                                                    (endpoint + "\n" + model + "\n" + dimensions)
                                                            .getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("嵌入指纹不可用", e);
        }
    }

    /**
     * @return 是否显式开启
     */
    @Override
    public boolean enabled() {
        return enabled;
    }

    /**
     * @return 固定向量空间指纹
     */
    @Override
    public String profile() {
        return profile;
    }

    /**
     * @return 输出维数
     */
    @Override
    public int dimensions() {
        return dimensions;
    }

    /**
     * 单文本请求，响应限制1MiB，校验维数、有限数值及非零向量。
     *
     * @param text 最长8000字符的文本
     * @return 经验证的向量及可空用量
     * @throws BusinessException 未启用、输入无效或协议失败
     */
    @Override
    public EmbeddingResult embed(String text) {
        if (!enabled) throw new BusinessException(503, "EMBEDDING_UNAVAILABLE", "嵌入服务未启用");
        if (text == null || text.isBlank() || text.length() > 8000)
            throw new BusinessException(422, "EMBEDDING_INPUT_INVALID", "嵌入文本不合法");
        try {
            var request =
                    new Request.Builder()
                            .url(endpoint)
                            .post(
                                    RequestBody.create(
                                            json.writeValueAsBytes(
                                                    Map.of("model", model, "input", text)),
                                            MediaType.parse("application/json")));
            if (!key.isBlank()) request.header("Authorization", "Bearer " + key);
            try (var response = client.newCall(request.build()).execute()) {
                if (response.code() != 200 || response.body() == null)
                    throw new IllegalStateException();
                byte[] raw = response.body().byteStream().readNBytes(1048577);
                if (raw.length > 1048576) throw new IllegalStateException();
                var root = json.readTree(raw);
                var data = root.path("data");
                if (!data.isArray() || data.size() != 1) throw new IllegalStateException();
                var vector = data.get(0).path("embedding");
                if (!vector.isArray() || vector.size() != dimensions)
                    throw new IllegalStateException();
                var values = new ArrayList<String>();
                double norm = 0;
                for (var n : vector) {
                    double v = n.asDouble();
                    if (!n.isNumber() || !Double.isFinite(v) || Math.abs(v) > Float.MAX_VALUE)
                        throw new IllegalStateException();
                    norm += v * v;
                    values.add(Double.toString(v));
                }
                if (!(norm > 0) || !Double.isFinite(norm)) throw new IllegalStateException();
                var usage = root.path("usage").path("prompt_tokens");
                Long tokens = usage.isIntegralNumber() ? usage.asLong() : null;
                if (tokens != null && tokens < 0) throw new IllegalStateException();
                return new EmbeddingResult("[" + String.join(",", values) + "]", tokens);
            }
        } catch (Exception e) {
            throw new BusinessException(503, "EMBEDDING_FAILED", "嵌入服务响应不可用");
        }
    }
}
