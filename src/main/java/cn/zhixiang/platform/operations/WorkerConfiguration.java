package cn.zhixiang.platform.operations;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableScheduling;
/** 只有 worker 开启调度；API 副本不运行后台任务。 */
@Configuration
@Profile("worker")
@EnableScheduling
public class WorkerConfiguration {}
