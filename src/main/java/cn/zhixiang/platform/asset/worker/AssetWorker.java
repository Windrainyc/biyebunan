package cn.zhixiang.platform.asset.worker;

import cn.zhixiang.platform.asset.service.AssetValidation;

import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 独立worker轮询持久任务，多个副本通过数据库租约协调。 */
@Component
@Profile("worker")
public class AssetWorker {
    private final AssetValidation validation;

    /**
     * 初始化worker。
     *
     * @param validation 工作单元
     */
    public AssetWorker(AssetValidation validation) {
        this.validation = validation;
    }

    /** 每次最多处理一个文件，避免无限批次影响优雅停止。 */
    @Scheduled(fixedDelayString = "${ZX_ASSET_POLL_MS:1000}")
    public void poll() {
        validation.runOne();
    }
}
