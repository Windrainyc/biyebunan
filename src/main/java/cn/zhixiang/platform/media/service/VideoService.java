package cn.zhixiang.platform.media.service;

import cn.zhixiang.platform.asset.dto.AssetDtos;
import cn.zhixiang.platform.asset.mapper.AssetGcMapper;
import cn.zhixiang.platform.asset.mapper.AssetMapper;
import cn.zhixiang.platform.asset.service.AssetService;
import cn.zhixiang.platform.configuration.dto.StorageDtos;
import cn.zhixiang.platform.configuration.mapper.StorageMapper;
import cn.zhixiang.platform.identity.model.Actor;
import cn.zhixiang.platform.media.mapper.VideoMapper;
import cn.zhixiang.platform.media.port.VideoProcessor;
import cn.zhixiang.platform.shared.exception.BusinessException;
import cn.zhixiang.platform.storage.port.ObjectStorage;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.util.UUID;

/** 录屏转码、固定副本及每次重新授权的播放签名，不覆盖源资产。 */
@Service
public class VideoService {
    private final VideoMapper tasks;
    private final AssetMapper assets;
    private final AssetService access;
    private final StorageMapper stores;
    private final ObjectStorage storage;
    private final VideoProcessor processor;
    private final TransactionTemplate tx;
    private final AssetGcMapper gc;

    /**
     * 创建处理用例。
     *
     * @param tasks 视频任务
     * @param assets 原资产
     * @param access 业务授权
     * @param stores 存储配置
     * @param storage 私有对象端口
     * @param processor 独立媒体进程
     * @param tx 短事务
     * @param gc 孤立输出登记
     */
    public VideoService(
            VideoMapper tasks,
            AssetMapper assets,
            AssetService access,
            StorageMapper stores,
            ObjectStorage storage,
            VideoProcessor processor,
            TransactionTemplate tx,
            AssetGcMapper gc) {
        this.tasks = tasks;
        this.assets = assets;
        this.access = access;
        this.stores = stores;
        this.storage = storage;
        this.processor = processor;
        this.tx = tx;
        this.gc = gc;
    }

    /**
     * 处理一个已扫描视频；源读、转码、上传都在事务外。
     *
     * @return 是否领取任务
     */
    public boolean runOne() {
        if (!processor.enabled()) return false;
        var task =
                tx.execute(
                        s -> {
                            tasks.seed();
                            return tasks.claim();
                        });
        if (task == null) return false;
        try {
            if (task.attempts() > 3)
                throw new BusinessException(503, "VIDEO_RETRY_EXHAUSTED", "视频处理次数达到限制");
            var asset = assets.get(task.assetId());
            if (asset == null || asset.deletedAt() != null || !asset.state().equals("READY"))
                throw new BusinessException(422, "VIDEO_SOURCE_UNAVAILABLE", "源视频不可用");
            var target = target(asset.storageId());
            var source =
                    new ObjectStorage.Metadata(
                            asset.size(), null, asset.objectVersion(), asset.mimeType());
            try (var input = storage.open(target, asset.privateKey(), source);
                    var output = processor.process(input, asset.size())) {
                String key =
                        "videos/verified/"
                                + asset.id()
                                + "/playback-"
                                + task.generation()
                                + "-"
                                + UUID.randomUUID();
                tx.executeWithoutResult(
                        s -> gc.trackMedia(asset.id(), asset.storageId(), key, task.generation()));
                ObjectStorage.Metadata saved;
                try (var stream = Files.newInputStream(output.file())) {
                    saved = storage.writeStream(target, key, stream, output.size(), "video/mp4");
                }
                tx.executeWithoutResult(
                        s -> {
                            var current = assets.lock(asset.id());
                            if (current == null
                                    || current.deletedAt() != null
                                    || !current.state().equals("READY")) {
                                tasks.fail(task, "VIDEO_SOURCE_UNAVAILABLE");
                                return;
                            }
                            tasks.complete(
                                    task,
                                    key,
                                    saved.versionId(),
                                    saved.size(),
                                    output.durationSeconds(),
                                    output.width(),
                                    output.height());
                        });
            }
        } catch (Exception e) {
            tx.executeWithoutResult(
                    s ->
                            tasks.fail(
                                    task,
                                    e instanceof BusinessException b
                                            ? b.code()
                                            : "VIDEO_PROCESSING_FAILED"));
        }
        return true;
    }

    /**
     * 复用原资产授权后签发当前播放副本；转码未成功不会冒充原文件。
     *
     * @param id 源资产
     * @param actor 真实身份
     * @return 一分钟私有URL
     * @throws BusinessException 无权或副本未就绪
     */
    public AssetDtos.Download playback(UUID id, Actor actor) {
        access.get(id, actor);
        var asset = assets.get(id);
        var task = tasks.get(id);
        if (asset == null
                || asset.deletedAt() != null
                || !asset.state().equals("READY")
                || task == null
                || !task.state().equals("SUCCEEDED"))
            throw new BusinessException(503, "VIDEO_NOT_READY", "播放副本尚未就绪");
        var signed =
                storage.download(target(asset.storageId()), task.outputKey(), task.objectVersion());
        return new AssetDtos.Download(signed.url(), signed.expiresAt());
    }

    private ObjectStorage.Target target(UUID id) {
        var config = stores.get(id);
        if (config == null || config.status() != StorageDtos.Status.ENABLED)
            throw new BusinessException(503, "STORAGE_UNAVAILABLE", "存储不可用");
        return new ObjectStorage.Target(
                config.provider().name(),
                config.endpoint(),
                config.region(),
                config.bucket(),
                config.credentialRef());
    }
}
