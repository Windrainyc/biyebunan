package cn.zhixiang.platform.knowledge.worker;

import cn.zhixiang.platform.knowledge.service.KnowledgeIndexer;

import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** worker模式独立调度，多个节点由数据库任务租约协调。 */
@Component
@Profile("worker")
public class IndexWorker {
    private final KnowledgeIndexer indexer;

    /**
     * 创建调度器。
     *
     * @param indexer 索引器
     */
    public IndexWorker(KnowledgeIndexer indexer) {
        this.indexer = indexer;
    }

    /** 每次执行至多一个已就绪任务。 */
    @Scheduled(fixedDelayString = "${ZX_INDEX_POLL_MS:1000}")
    public void poll() {
        indexer.runOne();
    }
}
