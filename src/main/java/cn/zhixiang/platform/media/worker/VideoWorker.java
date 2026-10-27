package cn.zhixiang.platform.media.worker;

import cn.zhixiang.platform.media.service.VideoService;

import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 视频worker单次一项，默认未启用处理器时不领取任务。 */
@Component
@Profile("worker")
public class VideoWorker {
    private final VideoService service;

    /**
     * 创建调度器。
     *
     * @param service 转码用例
     */
    public VideoWorker(VideoService service) {
        this.service = service;
    }

    /** 每轮处理一个视频，跨节点由数据库租约协调。 */
    @Scheduled(fixedDelay = 5000)
    public void poll() {
        service.runOne();
    }
}
