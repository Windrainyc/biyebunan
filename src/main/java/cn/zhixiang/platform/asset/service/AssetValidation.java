package cn.zhixiang.platform.asset.service;

import cn.zhixiang.platform.asset.entity.AssetEntity;
import cn.zhixiang.platform.asset.entity.AssetTaskJobEntity;
import cn.zhixiang.platform.asset.mapper.AssetGcMapper;
import cn.zhixiang.platform.asset.mapper.AssetMapper;
import cn.zhixiang.platform.asset.mapper.AssetTaskMapper;
import cn.zhixiang.platform.configuration.dto.StorageDtos;
import cn.zhixiang.platform.configuration.mapper.StorageMapper;
import cn.zhixiang.platform.shared.exception.BusinessException;
import cn.zhixiang.platform.shared.mapper.WriteMapper;
import cn.zhixiang.platform.storage.port.ContentScanner;
import cn.zhixiang.platform.storage.port.ObjectStorage;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

/** 真实存储校验工作单元；网络IO全部在租约领取与结果提交两个短事务之间。 */
@Service
public class AssetValidation {
    private final AssetTaskMapper tasks;
    private final AssetGcMapper garbage;
    private final AssetMapper assets;
    private final StorageMapper stores;
    private final ObjectStorage storage;
    private final ContentScanner scanner;
    private final TransactionTemplate tx;
    private final WriteMapper writes;

    /**
     * 创建验证器。
     *
     * @param garbage 副本目标登记
     * @param tasks 租约端口
     * @param assets 资产端口
     * @param stores 配置端口
     * @param storage 外部存储
     * @param scanner 扫描服务
     * @param tx 短事务
     * @param writes 审计事件
     */
    public AssetValidation(
            AssetGcMapper garbage,
            AssetTaskMapper tasks,
            AssetMapper assets,
            StorageMapper stores,
            ObjectStorage storage,
            ContentScanner scanner,
            TransactionTemplate tx,
            WriteMapper writes) {
        this.garbage = garbage;
        this.tasks = tasks;
        this.assets = assets;
        this.stores = stores;
        this.storage = storage;
        this.scanner = scanner;
        this.tx = tx;
        this.writes = writes;
    }

    /**
     * 执行至多一个任务；失败留在持久化队列，不假造安全结果。
     *
     * @return 是否领取过任务
     */
    public boolean runOne() {
        var job = tx.execute(s -> tasks.claim());
        if (job == null) return false;
        var row = assets.get(job.assetId());
        ObjectStorage.Target target = null;
        String privateKey = null;
        ObjectStorage.Metadata copy = null;
        try {
            if (row == null || row.deletedAt() != null || !row.state().equals("QUARANTINED")) {
                tx.executeWithoutResult(
                        s -> tasks.finish(job.assetId(), job.generation(), "SKIPPED"));
                return true;
            }
            if (job.attempts() > 8)
                throw new BusinessException(503, "VALIDATION_RETRY_EXHAUSTED", "校验重试次数已达上限");
            var config = stores.get(row.storageId());
            if (config == null || config.status() != StorageDtos.Status.ENABLED)
                throw new BusinessException(503, "STORAGE_UNAVAILABLE", "存储配置不可用");
            target =
                    new ObjectStorage.Target(
                            config.provider().name(),
                            config.endpoint(),
                            config.region(),
                            config.bucket(),
                            config.credentialRef());
            var meta = storage.stat(target, row.uploadKey());
            if (meta.size() != row.size() || !row.mimeType().equals(meta.contentType())) {
                reject(job, row, "ASSET_METADATA_MISMATCH");
                return true;
            }
            // 用户授权取消上传后摘要回读比对；仍检查对象元信息，非素材保留原扫描策略。
            if (!"MATERIAL".equals(row.ownerType())) {
                try (var input = storage.open(target, row.uploadKey(), meta)) {
                    if (!scanner.clean(input, meta.size())) {
                        reject(job, row, "ASSET_MALWARE_FOUND");
                        return true;
                    }
                }
            }
            privateKey =
                    AssetObjectKeys.prefix(row.mimeType(), row.filename())
                            + "verified/"
                            + row.id()
                            + "/"
                            + job.generation()
                            + "-"
                            + UUID.randomUUID();
            String attemptedKey = privateKey;
            tx.executeWithoutResult(
                    s -> garbage.track(row.id(), row.storageId(), attemptedKey, job.generation()));
            copy = storage.promote(target, row.uploadKey(), meta, privateKey);
            String key = privateKey, version = copy.versionId();
            boolean saved =
                    Boolean.TRUE.equals(
                            tx.execute(
                                    s -> {
                                        if (tasks.finish(job.assetId(), job.generation(), null)
                                                != 1) return false;
                                        if (tasks.ready(row.id(), key, version) != 1) return false;
                                        writes.audit(
                                                UUID.randomUUID(),
                                                row.createdBy(),
                                                "VALIDATED",
                                                "asset",
                                                row.id());
                                        writes.event(
                                                UUID.randomUUID(),
                                                row.id(),
                                                "asset.READY",
                                                "{\"id\":\"" + row.id() + "\"}");
                                        return true;
                                    }));
            if (!saved) storage.remove(target, privateKey, copy.versionId());
        } catch (Exception e) {
            String code = e instanceof BusinessException b ? b.code() : "ASSET_VALIDATION_FAILED";
            tx.executeWithoutResult(s -> tasks.retry(job.assetId(), job.generation(), code));
        }
        return true;
    }

    private void reject(AssetTaskJobEntity job, AssetEntity row, String code) {
        tx.executeWithoutResult(
                s -> {
                    if (tasks.finish(job.assetId(), job.generation(), code) == 1
                            && tasks.reject(row.id()) == 1) {
                        writes.audit(
                                UUID.randomUUID(), row.createdBy(), "REJECTED", "asset", row.id());
                        writes.event(
                                UUID.randomUUID(),
                                row.id(),
                                "asset.REJECTED",
                                "{\"id\":\"" + row.id() + "\"}");
                    }
                });
    }
}
