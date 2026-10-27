package cn.zhixiang.platform.knowledge.adapter;

import cn.zhixiang.platform.knowledge.port.DocumentTextExtractor;
import cn.zhixiang.platform.shared.exception.BusinessException;

import okhttp3.*;

import okio.BufferedSink;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** 通过受控本地Tika服务隔离PDF/Office解析，API进程不加载不可信文档解析器。 */
@Component
public class TikaDocumentExtractor implements DocumentTextExtractor {
    private final String endpoint;
    private final OkHttpClient client =
            new OkHttpClient.Builder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .readTimeout(Duration.ofSeconds(90))
                    .callTimeout(Duration.ofSeconds(100))
                    .followRedirects(false)
                    .followSslRedirects(false)
                    .retryOnConnectionFailure(false)
                    .build();

    /**
     * 读取部署固定端点，不允许用户指定解析地址。
     *
     * @param environment ZX_TIKA_ENDPOINT，空时显式不可用
     */
    public TikaDocumentExtractor(Environment environment) {
        endpoint = environment.getProperty("ZX_TIKA_ENDPOINT", "");
        if (!endpoint.isBlank()) {
            URI uri = URI.create(endpoint);
            if (uri.getHost() == null
                    || uri.getUserInfo() != null
                    || uri.getQuery() != null
                    || uri.getFragment() != null
                    || !java.util.Set.of("http", "https").contains(uri.getScheme()))
                throw new IllegalArgumentException("Tika部署端点不合法");
        }
    }

    /**
     * 流式发送扫描后的文件，限制输入50MiB、输出2MiB和500000字符。
     *
     * @param input 固定版本流
     * @param size 原始字节数
     * @param mime 媒体类型
     * @return 提取文本
     * @throws BusinessException 未配置、超限、无文本或服务失败
     */
    @Override
    public String extract(InputStream input, long size, String mime) {
        if (endpoint.isBlank()) throw fail("DOCUMENT_EXTRACTOR_UNAVAILABLE");
        if (size < 1 || size > 50L * 1024 * 1024) throw fail("DOCUMENT_EXTRACTION_TOO_LARGE");
        RequestBody body =
                new RequestBody() {
                    @Override
                    public MediaType contentType() {
                        return MediaType.parse("application/octet-stream");
                    }

                    @Override
                    public long contentLength() {
                        return size;
                    }

                    @Override
                    public void writeTo(BufferedSink sink) throws IOException {
                        byte[] buffer = new byte[32768];
                        long count = 0;
                        int n;
                        while ((n = input.read(buffer)) != -1) {
                            count += n;
                            if (count > size) throw new IOException("对象长度变化");
                            sink.write(buffer, 0, n);
                        }
                        if (count != size) throw new IOException("对象长度不符");
                    }
                };
        try (var response =
                client.newCall(
                                new Request.Builder()
                                        .url(endpoint)
                                        .header("Accept", "text/plain")
                                        .put(body)
                                        .build())
                        .execute()) {
            if (response.code() != 200 || response.body() == null)
                throw fail("DOCUMENT_EXTRACTION_FAILED");
            if (response.body().contentLength() > 2097152) throw fail("DOCUMENT_TEXT_TOO_LARGE");
            byte[] bytes = response.body().byteStream().readNBytes(2097153);
            if (bytes.length > 2097152) throw fail("DOCUMENT_TEXT_TOO_LARGE");
            String text = new String(bytes, StandardCharsets.UTF_8).strip();
            if (text.length() > 500000) throw fail("DOCUMENT_TEXT_TOO_LARGE");
            if (text.isBlank()) throw fail("DOCUMENT_TEXT_EMPTY");
            return text;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw fail("DOCUMENT_EXTRACTION_FAILED");
        }
    }

    private BusinessException fail(String code) {
        return new BusinessException(503, code, "文档文本提取未完成，请检查文档格式、大小或解析服务");
    }
}
