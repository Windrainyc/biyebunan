package cn.zhixiang.platform.configuration.worker;

import cn.zhixiang.platform.configuration.service.StorageMigrationService;

import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 每次只处理一个对象，降低小规格云主机压力。 */
@Component
@Profile("worker")
public class StorageMigrationWorker {
    private final StorageMigrationService service;

    /**
     * 按存储配置的权限、版本与生命周期约束执行操作。
     *
     * @param service 租约迁移
     */
    public StorageMigrationWorker(StorageMigrationService service) {
        this.service = service;
    }

    /** 失败由清单记录，不自动删除源对象。 */
    @Scheduled(fixedDelayString = "${ZX_STORAGE_MIGRATION_MS:5000}")
    public void poll() {
        service.processOne();
    }
}
