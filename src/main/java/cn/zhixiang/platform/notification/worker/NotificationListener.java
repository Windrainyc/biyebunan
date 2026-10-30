package cn.zhixiang.platform.notification.worker;

import cn.zhixiang.platform.notification.service.NotificationInbox;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** worker专用通知订阅；仅投递已在业务事务中登记的收件人快照。 */
@Component
@Profile("worker")
public class NotificationListener {
    private final NotificationInbox inbox;
    private final cn.zhixiang.platform.shared.observability.Telemetry telemetry;

    /**
     * 创建监听器。
     *
     * @param telemetry 消息父Span恢复
     * @param inbox 事务去重用例
     */
    public NotificationListener(
            NotificationInbox inbox,
            cn.zhixiang.platform.shared.observability.Telemetry telemetry) {
        this.inbox = inbox;
        this.telemetry = telemetry;
    }

    /**
     * 处理一条投递，异常交容器投递死信队列。
     *
     * @param message broker消息
     */
    @RabbitListener(queues = "zx.notifications", containerFactory = "notificationListenerFactory")
    public void receive(Message message) {
        Object parent = message.getMessageProperties().getHeaders().get("traceparent");
        try (var trace =
                telemetry.start(
                        "notification.consume",
                        parent instanceof String s ? s : null,
                        message.getMessageProperties().getMessageId())) {
            try {
                inbox.consume(message);
            } catch (RuntimeException ex) {
                trace.error("INBOX_FAILED");
                throw ex;
            }
        }
    }
}
