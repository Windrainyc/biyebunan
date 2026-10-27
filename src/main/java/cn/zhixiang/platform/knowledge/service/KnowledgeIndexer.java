package cn.zhixiang.platform.knowledge.service;

import cn.zhixiang.platform.knowledge.entity.IndexTaskEntity;
import cn.zhixiang.platform.knowledge.mapper.ContentMapper;
import cn.zhixiang.platform.knowledge.mapper.IndexMapper;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

/** 文本分片生成与代次校验；不使用付费embedding，也不生成伪造向量。 */
@Service
public class KnowledgeIndexer {
    private final cn.zhixiang.platform.asset.mapper.AssetMapper assets;
    private final cn.zhixiang.platform.configuration.mapper.StorageMapper stores;
    private final cn.zhixiang.platform.storage.port.ObjectStorage storage;
    private final cn.zhixiang.platform.knowledge.port.DocumentTextExtractor extractor;
    private final IndexMapper mapper;
    private final ContentMapper contents;
    private final TransactionTemplate tx;

    /**
     * 创建索引器。
     *
     * @param assets 已校验资产
     * @param stores 固定存储
     * @param storage 私有对象流
     * @param extractor 隔离文档解析
     * @param mapper 索引端口
     * @param contents 内容端口
     * @param tx 短事务
     */
    public KnowledgeIndexer(
            IndexMapper mapper,
            ContentMapper contents,
            TransactionTemplate tx,
            cn.zhixiang.platform.asset.mapper.AssetMapper assets,
            cn.zhixiang.platform.configuration.mapper.StorageMapper stores,
            cn.zhixiang.platform.storage.port.ObjectStorage storage,
            cn.zhixiang.platform.knowledge.port.DocumentTextExtractor extractor) {
        this.assets = assets;
        this.stores = stores;
        this.storage = storage;
        this.extractor = extractor;
        this.mapper = mapper;
        this.contents = contents;
        this.tx = tx;
    }

    /**
     * 执行一个任务；类型未支持时显式FAILED，不能报告已索引。
     *
     * @return 是否领取任务
     */
    public boolean runOne() {
        var task = tx.execute(s -> mapper.claim());
        if (task == null) return false;
        var source = mapper.source(task.snapshotId());
        try {
            if (source == null)
                throw new cn.zhixiang.platform.shared.exception.BusinessException(
                        404, "SOURCE_NOT_FOUND", "内容快照不存在");
            String text;
            if (source.type().equals("MARKDOWN")) text = Objects.toString(source.markdown(), "");
            else if (source.type().equals("DOCUMENT")) {
                var asset = source.assetId() == null ? null : assets.get(source.assetId());
                if (asset == null || asset.deletedAt() != null || !asset.state().equals("READY"))
                    throw new cn.zhixiang.platform.shared.exception.BusinessException(
                            422, "DOCUMENT_ASSET_UNAVAILABLE", "文档资产不可用");
                var store = stores.get(asset.storageId());
                if (store == null
                        || store.status()
                                != cn.zhixiang.platform.configuration.dto.StorageDtos.Status
                                        .ENABLED)
                    throw new cn.zhixiang.platform.shared.exception.BusinessException(
                            503, "STORAGE_UNAVAILABLE", "文档存储不可用");
                var target =
                        new cn.zhixiang.platform.storage.port.ObjectStorage.Target(
                                store.provider().name(),
                                store.endpoint(),
                                store.region(),
                                store.bucket(),
                                store.credentialRef());
                var metadata =
                        new cn.zhixiang.platform.storage.port.ObjectStorage.Metadata(
                                asset.size(), null, asset.objectVersion(), asset.mimeType());
                try (var input = storage.open(target, asset.privateKey(), metadata)) {
                    text = extractor.extract(input, asset.size(), asset.mimeType());
                }
            } else if (source.type().equals("VIDEO")) {
                // 视频仅建立发布标题的元数据索引；不能将标题命中冒充语音或画面转写。
                var asset = source.assetId() == null ? null : assets.get(source.assetId());
                if (asset == null || asset.deletedAt() != null || !asset.state().equals("READY"))
                    throw new cn.zhixiang.platform.shared.exception.BusinessException(
                            422, "VIDEO_ASSET_UNAVAILABLE", "视频资产不可用");
                text = "";
            } else
                throw new cn.zhixiang.platform.shared.exception.BusinessException(
                        503, "CONTENT_EXTRACTION_UNAVAILABLE", "该内容类型的文本提取尚未配置");
            complete(task, split(source.title() + "\n" + text), null);
        } catch (Exception e) {
            complete(
                    task,
                    List.of(),
                    e instanceof cn.zhixiang.platform.shared.exception.BusinessException b
                            ? b.code()
                            : "CONTENT_EXTRACTION_FAILED");
        }
        return true;
    }

    /**
     * 提交处理结果，先锁内容再更新任务，遵循发布入队锁序。
     *
     * @param task 当前租约
     * @param chunks 分片
     * @param error 可空错误码
     * @return 是否成功写入当前代次
     */
    public boolean complete(IndexTaskEntity task, List<String> chunks, String error) {
        return Boolean.TRUE.equals(
                tx.execute(
                        s -> {
                            var content = contents.lock(task.contentId());
                            boolean current =
                                    content != null
                                            && content.state().equals("PUBLISHED")
                                            && content.indexGeneration() == task.contentGeneration()
                                            && task.snapshotId()
                                                    .equals(content.publishedVersionId());
                            if (mapper.finish(
                                            task.id(),
                                            task.generation(),
                                            current
                                                    ? (error == null ? "SUCCEEDED" : "FAILED")
                                                    : "DEAD",
                                            current ? error : "STALE_GENERATION")
                                    != 1) return false;
                            if (!current) return false;
                            if (error != null) {
                                mapper.state(content.id(), task.contentGeneration(), "FAILED");
                                return false;
                            }
                            mapper.clear(content.id());
                            int n = 0;
                            for (String chunk : chunks)
                                mapper.chunk(UUID.randomUUID(), task, ++n, chunk);
                            mapper.state(content.id(), task.contentGeneration(), "READY");
                            return true;
                        }));
    }

    private List<String> split(String text) {
        List<String> result = new ArrayList<>();
        int[] points = text.codePoints().toArray();
        for (int i = 0; i < points.length; i += 1300) {
            int length = Math.min(1500, points.length - i);
            result.add(new String(points, i, length));
            if (i + length == points.length) break;
        }
        return result;
    }
}
