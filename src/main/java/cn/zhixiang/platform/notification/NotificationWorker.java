package cn.zhixiang.platform.notification;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
/** 独立worker投递站内信；最多每批50项，失败不打印通知正文。 */
@Component @Profile("worker")
public class NotificationWorker {
 private final NotificationService service;
 private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(NotificationWorker.class);
 /** 构造调度器。
  * @param service 事务投递端口 */
 public NotificationWorker(NotificationService service){this.service=service;}
 /** 多节点依靠SKIP LOCKED和收件箱唯一约束协调；失败回滚后下一轮接续。 */
 @Scheduled(fixedDelay=5000)
 public void poll(){try{for(int i=0;i<50&&service.processOne();i++){} }catch(RuntimeException e){LOG.warn("站内通知投递失败，异常类型：{}",e.getClass().getSimpleName());}}
}
