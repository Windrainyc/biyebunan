package cn.zhixiang.platform.probing.worker;

import cn.zhixiang.platform.probing.service.ConfigurationProbeService;

import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 独立worker领取只读探测任务；多副本通过数据库租约竞争。 */
@Component
@Profile("worker")
public class ProbeWorker {
    private final ConfigurationProbeService service;

    /**
     * 注入探测用例。
     *
     * @param service 持久任务
     */
    public ProbeWorker(ConfigurationProbeService service) {
        this.service = service;
    }

    /** 每次至多处理一项，API进程不执行。 */
    @Scheduled(fixedDelayString = "${ZX_PROBE_POLL_MS:1000}")
    public void poll() {
        service.runOne();
    }
}
