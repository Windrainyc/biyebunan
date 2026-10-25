package cn.zhixiang.platform.chat;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
/** 独立worker运行模型队列；多副本由数据库租约协调。 */
@Component @Profile("worker")
public class RunWorker {
 private final RunExecutor executor;private final RunControlService control;
 private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(RunWorker.class);
 /** 创建调度器。
 * @param executor 事务外协调器
 * @param control 失联恢复 */
 public RunWorker(RunExecutor executor,RunControlService control){this.executor=executor;this.control=control;}
 /** 每次处理一个运行；不在应用内自动重发未知付费请求。 */
 @Scheduled(fixedDelayString="${ZX_RUN_POLL_MS:1000}") public void poll(){try{executor.runOne();}catch(RuntimeException e){LOG.warn("模型任务未完成，保留断点；异常类型={}",e.getClass().getSimpleName());}}
 /** 定期把失联调用转入核对；与调用线程分开调度。 */
 @Scheduled(fixedDelayString="${ZX_RUN_RECOVERY_MS:5000}") public void recover(){try{control.expireOne();}catch(RuntimeException e){LOG.warn("模型核对调度失败；异常类型={}",e.getClass().getSimpleName());}}
}
