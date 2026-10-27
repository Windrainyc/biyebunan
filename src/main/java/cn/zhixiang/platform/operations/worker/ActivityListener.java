package cn.zhixiang.platform.operations.worker;

import cn.zhixiang.platform.operations.service.ActivityInbox;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** worker专用事件统计订阅；不是通用业务任务的伪成功处理器。 */
@Component
@Profile("worker")
public class ActivityListener {
    private final ActivityInbox inbox;

    /**
     * 创建监听器。
     *
     * @param inbox 事务去重用例
     */
    public ActivityListener(ActivityInbox inbox) {
        this.inbox = inbox;
    }

    /**
     * 处理一条投递，异常交容器投递死信队列。
     *
     * @param message broker消息
     */
    @RabbitListener(queues = "zx.activity", containerFactory = "activityListenerFactory")
    public void receive(Message message) {
        inbox.consume(message);
    }
}
