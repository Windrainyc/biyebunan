package cn.zhixiang.platform.callback.service;

import cn.zhixiang.platform.asset.dto.AssetDtos;
import cn.zhixiang.platform.asset.entity.AssetEntity;
import cn.zhixiang.platform.asset.mapper.AssetMapper;
import cn.zhixiang.platform.callback.dto.OssCallbackRequest;
import cn.zhixiang.platform.callback.mapper.OssCallbackMapper;
import cn.zhixiang.platform.callback.security.OssCallbackSignature;
import cn.zhixiang.platform.configuration.dto.StorageDtos;
import cn.zhixiang.platform.configuration.mapper.StorageMapper;
import cn.zhixiang.platform.shared.exception.BusinessException;
import cn.zhixiang.platform.shared.mapper.WriteMapper;
import cn.zhixiang.platform.storage.port.ObjectStorage;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** OSS供应商回调；签名不是文件安全证明，验真后仍进入隔离扫描流程。 */
@Service
public class OssCallbackService {
    private final OssCallbackSignature signatures;
    private final OssCallbackMapper receipts;
    private final AssetMapper assets;
    private final StorageMapper stores;
    private final ObjectStorage storage;
    private final WriteMapper writes;
    private final TransactionTemplate tx;

    /**
     * 注入签名信任、资产和事务端口。
     *
     * @param signatures 验签器
     * @param receipts 去重
     * @param assets 上传会话
     * @param stores 存储配置
     * @param storage 实际对象验真
     * @param writes 审计
     * @param manager 短事务
     */
    public OssCallbackService(
            OssCallbackSignature signatures,
            OssCallbackMapper receipts,
            AssetMapper assets,
            StorageMapper stores,
            ObjectStorage storage,
            WriteMapper writes,
            PlatformTransactionManager manager) {
        this.signatures = signatures;
        this.receipts = receipts;
        this.assets = assets;
        this.stores = stores;
        this.storage = storage;
        this.writes = writes;
        tx = new TransactionTemplate(manager);
    }

    /**
     * 验签、解析和HEAD在事务外，最终以资产行锁保证一次隔离副作用。
     *
     * @param body 原始表单
     * @param signature 签名
     * @param keyUrl 公钥来源
     * @param query 原始查询串
     * @return 当前资产处理状态
     * @throws BusinessException 签名、对象或会话不匹配
     */
    public AssetDtos.View receive(byte[] body, String signature, String keyUrl, String query) {
        signatures.verify("/api/v1/callbacks/oss", query, body, signature, keyUrl);
        var w = parse(body);
        String digest;
        try {
            digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        var asset = assets.upload(w.uploadId());
        validate(asset, w);
        String prior = receipts.digest(w.uploadId());
        if (prior != null) {
            if (!prior.equals(digest)) throw conflict();
            return view(asset);
        }
        var store = stores.get(asset.storageId());
        if (store == null
                || store.provider() != StorageDtos.Provider.ALIYUN_OSS
                || store.status() != StorageDtos.Status.ENABLED
                || !store.bucket().equals(w.bucket())) throw mismatch();
        var target =
                new ObjectStorage.Target(
                        store.provider().name(),
                        store.endpoint(),
                        store.region(),
                        store.bucket(),
                        store.credentialRef());
        var actual = storage.stat(target, w.object());
        if (actual.size() != w.size() || !Objects.equals(actual.etag(), w.etag())) throw mismatch();
        return tx.execute(
                s -> {
                    var current = assets.lock(asset.id());
                    validate(current, w);
                    var configuration = stores.get(current.storageId());
                    if (configuration == null
                            || configuration.version() != store.version()
                            || configuration.status() != StorageDtos.Status.ENABLED)
                        throw mismatch();
                    String existing = receipts.digest(w.uploadId());
                    if (existing != null) {
                        if (!existing.equals(digest)) throw conflict();
                        return view(current);
                    }
                    if (!current.state().equals("UPLOADING")
                            && !current.state().equals("QUARANTINED"))
                        throw new BusinessException(409, "UPLOAD_STATE_CONFLICT", "上传状态不允许回调");
                    if (!assets.completable(current.id()))
                        throw new BusinessException(422, "UPLOAD_EXPIRED", "上传会话已过期");
                    assets.quarantine(current.id(), current.createdBy());
                    assets.enqueue(current.id());
                    receipts.insert(w.uploadId(), current.id(), digest, actual.versionId());
                    writes.audit(
                            UUID.randomUUID(),
                            current.createdBy(),
                            "OSS_CALLBACK_VERIFIED",
                            "asset",
                            current.id());
                    return view(assets.get(current.id()));
                });
    }

    private OssCallbackRequest parse(byte[] body) {
        try {
            Map<String, String> fields = new HashMap<>();
            for (String pair : new String(body, StandardCharsets.UTF_8).split("&", -1)) {
                String[] parts = pair.split("=", 2);
                if (parts.length != 2) throw new IllegalArgumentException();
                String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                        value = URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
                if (fields.putIfAbsent(key, value) != null) throw new IllegalArgumentException();
            }
            if (!fields.keySet().equals(Set.of("uploadId", "bucket", "object", "size", "etag")))
                throw new IllegalArgumentException();
            long size = Long.parseLong(fields.get("size"));
            if (size < 0 || fields.get("etag").isBlank() || fields.get("etag").length() > 200)
                throw new IllegalArgumentException();
            return new OssCallbackRequest(
                    UUID.fromString(fields.get("uploadId")),
                    fields.get("bucket"),
                    fields.get("object"),
                    size,
                    fields.get("etag"));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(400, "OSS_CALLBACK_INVALID", "回调表单字段不合法");
        }
    }

    private void validate(AssetEntity a, OssCallbackRequest w) {
        if (a == null
                || a.deletedAt() != null
                || !a.uploadKey().equals(w.object())
                || a.size() != w.size()) throw mismatch();
    }

    private BusinessException mismatch() {
        return new BusinessException(422, "OSS_OBJECT_MISMATCH", "回调对象与上传会话不匹配");
    }

    private BusinessException conflict() {
        return new BusinessException(409, "OSS_CALLBACK_CONFLICT", "同一上传会话收到不同回调");
    }

    private AssetDtos.View view(AssetEntity a) {
        return new AssetDtos.View(
                a.id(),
                a.version(),
                a.createdAt(),
                a.updatedAt(),
                a.filename(),
                a.size(),
                a.sha256(),
                a.mimeType(),
                a.state(),
                a.storageId(),
                a.materialCategoryId());
    }
}
