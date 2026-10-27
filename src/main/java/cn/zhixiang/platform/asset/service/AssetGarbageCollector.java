package cn.zhixiang.platform.asset.service;

import cn.zhixiang.platform.asset.mapper.*;
import cn.zhixiang.platform.configuration.dto.StorageDtos;
import cn.zhixiang.platform.configuration.mapper.StorageMapper;
import cn.zhixiang.platform.shared.exception.BusinessException;
import cn.zhixiang.platform.storage.port.ObjectStorage;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 只清理已登记退休键，外部删除不占数据库事务；每日复查迟到副本。 */
@Service
public class AssetGarbageCollector {
    private final AssetGcMapper mapper;
    private final AssetMapper assets;
    private final StorageMapper stores;
    private final ObjectStorage storage;
    private final TransactionTemplate tx;

    /**
     * 注入回收端口。
     *
     * @param mapper 持久租约
     * @param assets 资产和引用保护
     * @param stores 固定配置
     * @param storage 对象版本删除
     * @param manager 短事务
     */
    public AssetGarbageCollector(
            AssetGcMapper mapper,
            AssetMapper assets,
            StorageMapper stores,
            ObjectStorage storage,
            PlatformTransactionManager manager) {
        this.mapper = mapper;
        this.assets = assets;
        this.stores = stores;
        this.storage = storage;
        tx = new TransactionTemplate(manager);
    }

    /**
     * 有界登记与处理一个对象，不使用目录级删除。
     *
     * @return 是否领取任务
     */
    public boolean runOne() {
        var job =
                tx.execute(
                        s -> {
                            mapper.registerUploads();
                            mapper.registerPrivate();
                            return mapper.claim();
                        });
        if (job == null) return false;
        try {
            var target =
                    tx.execute(
                            s -> {
                                var asset = assets.lock(job.assetId());
                                if (asset == null
                                        || !mapper.eligible(job)
                                        || !job.kind().equals("UPLOAD")
                                                && !(job.kind().equals("ATTEMPT")
                                                        && !java.util.Objects.equals(
                                                                job.objectKey(),
                                                                asset.privateKey()))
                                                && assets.referenced(job.assetId()))
                                    throw new BusinessException(
                                            409, "GC_NOT_RETIRED", "对象尚未退休或仍被引用");
                                var store = stores.get(job.storageId());
                                if (store == null || store.status() != StorageDtos.Status.ENABLED)
                                    throw new BusinessException(
                                            503, "GC_STORAGE_UNAVAILABLE", "回收存储不可用");
                                return new ObjectStorage.Target(
                                        store.provider().name(),
                                        store.endpoint(),
                                        store.region(),
                                        store.bucket(),
                                        store.credentialRef());
                            });
            boolean done = storage.purge(target, job.objectKey());
            tx.executeWithoutResult(
                    s ->
                            mapper.finish(
                                    job.id(), job.generation(), done ? null : "GC_MORE_VERSIONS"));
        } catch (BusinessException e) {
            tx.executeWithoutResult(s -> mapper.finish(job.id(), job.generation(), e.code()));
        } catch (Exception e) {
            tx.executeWithoutResult(
                    s -> mapper.finish(job.id(), job.generation(), "GC_STORAGE_FAILED"));
        }
        return true;
    }
}
