package cn.zhixiang.platform.notification.worker;

import cn.zhixiang.platform.notification.service.NotificationService;

import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 站内信低频补偿；Rabbit消息为主，补偿处理历史积压或拓扑部署前的事件。 */
@Component
@Profile("worker")
public class NotificationWorker {
    private final NotificationService service;
    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(NotificationWorker.class);

    /**
     * 构造调度器。
     *
     * @param service 事务投递端口
     */
    public NotificationWorker(NotificationService service) {
        this.service = service;
    }

    /** 多节点依靠SKIP LOCKED和收件箱唯一约束协调；失败回滚后下一轮接续。 */
    @Scheduled(fixedDelayString = "${ZX_NOTIFICATION_REPAIR_MS:60000}")
    public void poll() {
        try {
            for (int i = 0; i < 50 && service.processOne(); i++) {}
        } catch (RuntimeException e) {
            LOG.warn("站内通知投递失败，异常类型：{}", e.getClass().getSimpleName());
        }
    }
}
