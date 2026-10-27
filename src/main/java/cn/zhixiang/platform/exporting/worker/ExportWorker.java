package cn.zhixiang.platform.exporting.worker;

import cn.zhixiang.platform.exporting.service.ExportCleanup;
import cn.zhixiang.platform.exporting.service.ExportService;

import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 独立worker运行报告和过期对象清理，API不运行后台任务。 */
@Component
@Profile("worker")
public class ExportWorker {
    private final ExportService service;
    private final ExportCleanup cleanup;

    /**
     * 注入工作单元。
     *
     * @param service 报告
     * @param cleanup 过期对象
     */
    public ExportWorker(ExportService service, ExportCleanup cleanup) {
        this.service = service;
        this.cleanup = cleanup;
    }

    /** 每次领取一项，多个副本由数据库租约协调。 */
    @Scheduled(fixedDelayString = "${ZX_EXPORT_POLL_MS:1000}")
    public void poll() {
        service.runOne();
    }

    /** 限定清理本模块登记的过期对象。 */
    @Scheduled(fixedDelayString = "${ZX_EXPORT_CLEANUP_MS:30000}")
    public void clean() {
        cleanup.runOne();
    }
}
