package cn.zhixiang.platform.notification.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;

/** 通知独立订阅队列，不能抢走通用任务队列的消息；失败进入持久死信队列。 */
@Configuration
@Profile("worker")
public class NotificationConsumerConfiguration {
    /** 创建worker消息拓扑配置，不在构造阶段连接broker。 */
    public NotificationConsumerConfiguration() {}

    /**
     * 固定隔离命名的持久队列和死信路由；无需修改已有zx.tasks。
     *
     * @param exchange 既有事件交换机
     * @param routingKey 事件路由
     * @return 待broker声明的对象
     */
    @Bean
    public Declarables notificationTopology(
            @Value("${ZX_EVENT_EXCHANGE:zx.events}") String exchange,
            @Value("${ZX_EVENT_ROUTING_KEY:task}") String routingKey) {
        var queue =
                QueueBuilder.durable("zx.notifications")
                        .deadLetterExchange("zx.notifications.dead.exchange")
                        .deadLetterRoutingKey("dead")
                        .build();
        var dead = new Queue("zx.notifications.dead", true);
        var dlx = new DirectExchange("zx.notifications.dead.exchange", true, false);
        return new Declarables(
                queue,
                dead,
                dlx,
                new Binding(
                        "zx.notifications",
                        Binding.DestinationType.QUEUE,
                        exchange,
                        routingKey,
                        null),
                BindingBuilder.bind(dead).to(dlx).with("dead"));
    }

    /**
     * AUTO模式仅在事务用例返回后ACK；失败不热循环重入队，转入死信待受控处理。
     *
     * @param factory broker连接
     * @return 独立监听工厂
     */
    @Bean
    public SimpleRabbitListenerContainerFactory notificationListenerFactory(
            ConnectionFactory factory) {
        var result = new SimpleRabbitListenerContainerFactory();
        result.setConnectionFactory(factory);
        result.setConcurrentConsumers(1);
        result.setMaxConcurrentConsumers(2);
        result.setPrefetchCount(10);
        result.setDefaultRequeueRejected(false);
        result.setAcknowledgeMode(AcknowledgeMode.AUTO);
        return result;
    }
}
