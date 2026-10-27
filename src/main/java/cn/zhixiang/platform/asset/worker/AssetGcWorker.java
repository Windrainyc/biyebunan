package cn.zhixiang.platform.asset.worker;

import cn.zhixiang.platform.asset.service.AssetGarbageCollector;

import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** worker专属对象回收调度，多节点通过数据库租约分配。 */
@Component
@Profile("worker")
public class AssetGcWorker {
    private final AssetGarbageCollector collector;

    /**
     * 注入回收用例。
     *
     * @param collector 回收器
     */
    public AssetGcWorker(AssetGarbageCollector collector) {
        this.collector = collector;
    }

    /** 每三十秒处理一项，避免大量清理挤占前台IO。 */
    @Scheduled(fixedDelayString = "${ZX_ASSET_GC_MS:30000}")
    public void poll() {
        collector.runOne();
    }
}
