package cn.zhixiang.platform.billing.worker;

import cn.zhixiang.platform.billing.service.ReconciliationService;

import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 独立worker核对已保存证据；多个节点靠事务锁复查去重。 */
@Component
@Profile("worker")
public class ReconciliationWorker {
    private final ReconciliationService service;
    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(ReconciliationWorker.class);

    /**
     * 创建调度。
     *
     * @param service 核对用例
     */
    public ReconciliationWorker(ReconciliationService service) {
        this.service = service;
    }

    /** 处理一个排队任务；基础设施失败回滚，禁止记录证据正文。 */
    @Scheduled(fixedDelayString = "${ZX_RECONCILIATION_POLL_MS:5000}")
    public void poll() {
        try {
            service.processOne();
        } catch (RuntimeException error) {
            LOG.warn("用量核对暂未完成；异常类型={}", error.getClass().getSimpleName());
        }
    }
}
