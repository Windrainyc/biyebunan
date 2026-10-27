package cn.zhixiang.platform.knowledge.worker;

import cn.zhixiang.platform.knowledge.service.EmbeddingService;

import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 单分片后台嵌入调度；由数据库协调多副本。 */
@Component
@Profile("worker")
public class EmbeddingWorker {
    private final EmbeddingService service;

    /**
     * 创建调度器。
     *
     * @param service 事务外执行用例
     */
    public EmbeddingWorker(EmbeddingService service) {
        this.service = service;
    }

    /** 每轮处理一片，默认未启用嵌入客户端时不产生外部请求。 */
    @Scheduled(fixedDelay = 5000)
    public void poll() {
        service.runOne();
    }
}
