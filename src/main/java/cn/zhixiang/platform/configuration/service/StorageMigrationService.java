package cn.zhixiang.platform.configuration.service;

import cn.zhixiang.platform.configuration.dto.StorageMigrationView;
import cn.zhixiang.platform.configuration.entity.*;
import cn.zhixiang.platform.configuration.mapper.*;
import cn.zhixiang.platform.shared.exception.BusinessException;
import cn.zhixiang.platform.shared.mapper.WriteMapper;
import cn.zhixiang.platform.storage.port.ObjectStorage;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

/** 迁移网络IO在事务外执行；先完整校验副本再CAS换位置，保留源对象。 */
@Service
public class StorageMigrationService {
    private final StorageMigrationMapper mapper;
    private final StorageMapper stores;
    private final OssService oss;
    private final ObjectStorage storage;
    private final WriteMapper writes;
    private final TransactionTemplate tx;

    /**
     * 按存储配置的权限、版本与生命周期约束执行操作。
     *
     * @param mapper 清单
     * @param stores 配置
     * @param oss 检查状态
     * @param storage 存储端口
     * @param writes 审计
     * @param tm 短事务
     */
    public StorageMigrationService(
            StorageMigrationMapper mapper,
            StorageMapper stores,
            OssService oss,
            ObjectStorage storage,
            WriteMapper writes,
            PlatformTransactionManager tm) {
        this.mapper = mapper;
        this.stores = stores;
        this.oss = oss;
        this.storage = storage;
        this.writes = writes;
        tx = new TransactionTemplate(tm);
    }

    /**
     * 按存储配置的权限、版本与生命周期约束执行操作。
     *
     * @return 真实数据库计数
     */
    public StorageMigrationView status() {
        return new StorageMigrationView(
                mapper.count("QUEUED"),
                mapper.count("RUNNING"),
                mapper.count("DONE"),
                mapper.count("FAILED"),
                mapper.remainingAssets(),
                mapper.remainingExports(),
                false);
    }

    /**
     * 按存储配置的权限、版本与生命周期约束执行操作。
     *
     * @param id 目标
     * @param version 凭证版本
     * @param actor 管理员
     * @return 进度
     */
    public StorageMigrationView start(UUID id, long version, UUID actor) {
        return tx.execute(
                s -> {
                    var state = oss.activate(id, version, actor);
                    mapper.enqueue(state.id());
                    writes.audit(
                            UUID.randomUUID(), actor, "MIGRATION_QUEUED", "storage_config", id);
                    return status();
                });
    }

    /**
     * 按存储配置的权限、版本与生命周期约束执行操作。
     *
     * @return 是否领取到任务
     */
    public boolean processOne() {
        var task =
                tx.execute(
                        s -> {
                            var m = mapper.next();
                            if (m == null) return null;
                            long g = mapper.claim(m.assetId());
                            return new StorageMigrationEntity(
                                    m.assetId(),
                                    m.sourceId(),
                                    m.targetId(),
                                    m.sourceKey(),
                                    m.sourceVersion(),
                                    m.sourceAssetVersion(),
                                    g,
                                    m.size(),
                                    m.sha256(),
                                    m.mimeType());
                        });
        if (task == null) return false;
        String key =
                "migrated/" + task.assetId() + "/" + task.generation() + "/" + UUID.randomUUID();
        String version = null;
        tx.executeWithoutResult(
                s -> mapper.attempt(task.assetId(), task.generation(), task.targetId(), key));
        try {
            var source = target(stores.get(task.sourceId()));
            var dest = target(stores.get(task.targetId()));
            var head = storage.stat(source, task.sourceKey());
            var fixed =
                    new ObjectStorage.Metadata(
                            task.size(), head.etag(), task.sourceVersion(), task.mimeType());
            if (head.size() != task.size()
                    || !task.sha256().equals(storage.sha256(source, task.sourceKey(), fixed)))
                throw new BusinessException(409, "MIGRATION_SOURCE_MISMATCH", "源文件不一致");
            ObjectStorage.Metadata copied;
            try (var in = storage.open(source, task.sourceKey(), fixed)) {
                copied = storage.writeStream(dest, key, in, task.size(), task.mimeType());
            }
            version = copied.versionId();
            if (copied.size() != task.size()
                    || !task.sha256().equals(storage.sha256(dest, key, copied)))
                throw new BusinessException(409, "MIGRATION_HASH_MISMATCH", "副本摘要不一致");
            final String v = version;
            tx.executeWithoutResult(
                    s -> {
                        if (mapper.held(task.assetId(), task.generation()) == null
                                || mapper.switchAsset(task, key, v) != 1)
                            throw new BusinessException(
                                    409, "MIGRATION_VERSION_CHANGED", "资产已变化，保留原位置");
                        mapper.finish(task.assetId(), task.generation(), key, v, null);
                        writes.audit(
                                UUID.randomUUID(),
                                null,
                                "STORAGE_MIGRATED",
                                "asset",
                                task.assetId());
                    });
        } catch (Exception e) {
            String error = e instanceof BusinessException b ? b.code() : "MIGRATION_IO_FAILED";
            String v = version;
            tx.executeWithoutResult(
                    s -> mapper.finish(task.assetId(), task.generation(), key, v, error));
        }
        return true;
    }

    private ObjectStorage.Target target(StorageEntity s) {
        if (s == null) throw new BusinessException(503, "STORAGE_UNAVAILABLE", "存储配置不存在");
        return new ObjectStorage.Target(
                s.provider().name(), s.endpoint(), s.region(), s.bucket(), s.credentialRef());
    }
}
