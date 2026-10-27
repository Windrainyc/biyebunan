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
    private final IndexMapper mapper;
    private final ContentMapper contents;
    private final TransactionTemplate tx;

    /**
     * 创建索引器。
     *
     * @param mapper 索引端口
     * @param contents 内容端口
     * @param tx 短事务
     */
    public KnowledgeIndexer(IndexMapper mapper, ContentMapper contents, TransactionTemplate tx) {
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
        String error =
                source == null
                        ? "SOURCE_NOT_FOUND"
                        : !source.type().equals("MARKDOWN")
                                ? "CONTENT_EXTRACTION_UNAVAILABLE"
                                : null;
        List<String> chunks =
                error == null
                        ? split(source.title() + "\n" + Objects.toString(source.markdown(), ""))
                        : List.of();
        complete(task, chunks, error);
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
