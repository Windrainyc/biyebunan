package cn.zhixiang.platform.notification.worker;

import cn.zhixiang.platform.notification.service.NotificationRetentionService;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 默认关闭的通知正文保留任务；部署确定保留期后以环境变量启用。 */
@Component
@Profile("worker")
public class NotificationRetentionWorker {
    private final NotificationRetentionService service;
    private final int days;

    /**
     * 初始化通知保留组件。
     *
     * @param service 有界清理用例
     * @param days ZX_NOTIFICATION_RETENTION_DAYS，默认0关闭
     */
    public NotificationRetentionWorker(
            NotificationRetentionService service,
            @Value("${ZX_NOTIFICATION_RETENTION_DAYS:0}") int days) {
        if (days != 0 && (days < 30 || days > 3650))
            throw new IllegalArgumentException("通知保留期配置非法");
        this.service = service;
        this.days = days;
    }

    /** 每分钟执行一批，不打印正文；未启用时不会修改任何数据。 */
    @Scheduled(fixedDelay = 60000)
    public void poll() {
        service.runBatch(days);
    }
}
