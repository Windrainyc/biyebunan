package cn.zhixiang.platform.preview.service;

import cn.zhixiang.platform.asset.mapper.AssetMapper;
import cn.zhixiang.platform.asset.service.AssetService;
import cn.zhixiang.platform.configuration.dto.StorageDtos;
import cn.zhixiang.platform.configuration.mapper.StorageMapper;
import cn.zhixiang.platform.identity.model.Actor;
import cn.zhixiang.platform.preview.dto.DocumentPreview;
import cn.zhixiang.platform.shared.exception.BusinessException;
import cn.zhixiang.platform.storage.port.ObjectStorage;

import okhttp3.*;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Semaphore;

/** Office私有预览：每次鉴权，转换不占数据库事务，不公开kkFileView缓存或URL代理。 */
@Service
public class DocumentPreviewService {
    private static final int LIMIT = 20 * 1024 * 1024;
    private final AssetService access;
    private final AssetMapper assets;
    private final StorageMapper stores;
    private final ObjectStorage storage;
    private final String endpoint;
    private final String tokenFile;
    private final Semaphore slots = new Semaphore(2);
    private final OkHttpClient client =
            new OkHttpClient.Builder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .readTimeout(Duration.ofSeconds(65))
                    .callTimeout(Duration.ofSeconds(90))
                    .followRedirects(false)
                    .followSslRedirects(false)
                    .build();

    /**
     * 创建用例，默认无配置时显式不可用。
     *
     * @param access 原资产授权
     * @param assets 固定版本元数据
     * @param stores 私有存储配置
     * @param storage 对象读取端口
     * @param endpoint 运维固定转换服务地址，不接受客户端URL
     * @param tokenFile 受保护的转换令牌文件
     */
    public DocumentPreviewService(
            AssetService access,
            AssetMapper assets,
            StorageMapper stores,
            ObjectStorage storage,
            @Value("${ZX_DOCUMENT_PREVIEW_ENDPOINT:}") String endpoint,
            @Value("${ZX_DOCUMENT_PREVIEW_TOKEN_FILE:}") String tokenFile) {
        this.access = access;
        this.assets = assets;
        this.stores = stores;
        this.storage = storage;
        this.endpoint = endpoint;
        this.tokenFile = tokenFile;
    }

    /**
     * 将已授权且扫描通过的Office资产转换为PDF；转换后重新验证权限和版本。
     *
     * @param id 源资产ID
     * @param actor 服务端身份
     * @return 有界PDF响应
     * @throws BusinessException 无权、未就绪、超限或转换不可用
     */
    public DocumentPreview preview(UUID id, Actor actor) {
        access.get(id, actor);
        var asset = assets.get(id);
        if (asset == null || !"READY".equals(asset.state()) || asset.deletedAt() != null)
            throw new BusinessException(422, "PREVIEW_SOURCE_UNAVAILABLE", "文件尚未通过校验");
        String name = asset.filename();
        String ext = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        if (!Set.of("doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "rtf")
                .contains(ext))
            throw new BusinessException(422, "PREVIEW_FORMAT_UNSUPPORTED", "当前格式不支持Office预览");
        if (asset.size() < 1 || asset.size() > LIMIT)
            throw new BusinessException(422, "PREVIEW_SIZE_LIMIT", "在线文档预览支持20MB以内文件");
        if (endpoint.isBlank() || tokenFile.isBlank()) throw unavailable();
        if (!slots.tryAcquire()) throw new BusinessException(503, "PREVIEW_BUSY", "预览任务较多，请稍后重试");
        try {
            var config = stores.get(asset.storageId());
            if (config == null || config.status() != StorageDtos.Status.ENABLED)
                throw unavailable();
            var target =
                    new ObjectStorage.Target(
                            config.provider().name(),
                            config.endpoint(),
                            config.region(),
                            config.bucket(),
                            config.credentialRef());
            byte[] source;
            try (var input =
                    storage.open(
                            target,
                            asset.privateKey(),
                            new ObjectStorage.Metadata(
                                    asset.size(), null, asset.objectVersion(), asset.mimeType()))) {
                source = input.readNBytes(LIMIT + 1);
            }
            if (source.length != asset.size()) throw unavailable();
            String token = Files.readString(Path.of(tokenFile)).trim();
            if (token.length() < 32) throw unavailable();
            var request =
                    new Request.Builder()
                            .url(URI.create(endpoint).resolve("/internal/convert").toString())
                            .header("X-Conversion-Token", token)
                            .header("X-Document-Extension", ext)
                            .post(
                                    RequestBody.create(
                                            source, MediaType.get("application/octet-stream")))
                            .build();
            byte[] pdf;
            try (var response = client.newCall(request).execute()) {
                if (response.code() != 200 || response.body() == null) throw unavailable();
                pdf = response.body().byteStream().readNBytes(LIMIT + 1);
            }
            if (pdf.length < 5
                    || pdf.length > LIMIT
                    || !new String(pdf, 0, 5, java.nio.charset.StandardCharsets.US_ASCII)
                            .equals("%PDF-")) throw unavailable();
            access.get(id, actor);
            var current = assets.get(id);
            if (current == null
                    || current.deletedAt() != null
                    || !"READY".equals(current.state())
                    || current.version() != asset.version())
                throw new BusinessException(409, "PREVIEW_SOURCE_CHANGED", "文件已变化，请重新打开预览");
            return new DocumentPreview(Base64.getEncoder().encodeToString(pdf));
        } catch (BusinessException e) {
            throw e;

        } catch (Exception e) {
            throw unavailable();
        } finally {
            slots.release();
        }
    }

    private BusinessException unavailable() {
        return new BusinessException(503, "DOCUMENT_PREVIEW_UNAVAILABLE", "文档预览暂不可用，可下载原文件或稍后重试");
    }
}
