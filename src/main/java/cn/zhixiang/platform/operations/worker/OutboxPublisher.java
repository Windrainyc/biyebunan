package cn.zhixiang.platform.operations.worker;

import cn.zhixiang.platform.operations.mapper.OutboxMapper;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/** 至少一次事件发布；confirm 后才能标记 SENT，返回消息或不确定结果保留租约等待重试。 */
@Component
@Profile("worker")
public class OutboxPublisher {
    private final String exchange;
    private final String routingKey;
    private final OutboxMapper mapper;
    private final RabbitTemplate rabbit;
    private final JsonMapper json;
    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(OutboxPublisher.class);

    /**
     * 初始化模块组件，所需端口和配置由构造器注入。
     *
     * @param mapper outbox数据
     * @param rabbit 启用confirm/mandatory的客户端
     * @param json JSON编码器
     * @param exchange 发布交换机
     * @param routingKey 路由键
     */
    public OutboxPublisher(
            OutboxMapper mapper,
            RabbitTemplate rabbit,
            JsonMapper json,
            @org.springframework.beans.factory.annotation.Value("${ZX_EVENT_EXCHANGE:zx.events}")
                    String exchange,
            @org.springframework.beans.factory.annotation.Value("${ZX_EVENT_ROUTING_KEY:task}")
                    String routingKey) {
        this.exchange = exchange;
        this.routingKey = routingKey;
        this.mapper = mapper;
        this.rabbit = rabbit;
        this.json = json;
        rabbit.setMandatory(true);
    }

    /** 定期领取待发布事件；无全局进程锁，多副本通过数据库 SKIP LOCKED 协作。 */
    @Scheduled(fixedDelayString = "${ZX_OUTBOX_DELAY_MS:2000}")
    public void publish() {
        for (var e : mapper.claim()) {
            try {
                byte[] body =
                        json.writeValueAsBytes(
                                Map.of(
                                        "eventId",
                                        e.id(),
                                        "aggregateId",
                                        e.aggregateId(),
                                        "type",
                                        e.eventType(),
                                        "data",
                                        json.readTree(e.payload())));
                MessageProperties props = new MessageProperties();
                props.setContentType("application/json");
                props.setMessageId(e.id().toString());
                props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                CorrelationData correlation = new CorrelationData(e.id() + ":" + e.generation());
                rabbit.send(exchange, routingKey, new Message(body, props), correlation);
                var confirm = correlation.getFuture().get(3, TimeUnit.SECONDS);
                if (confirm.isAck() && correlation.getReturned() == null)
                    mapper.sent(e.id(), e.generation());
                else
                    LOG.warn(
                            "outbox publish unconfirmed eventId={} generation={}",
                            e.id(),
                            e.generation());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception ex) {
                LOG.warn(
                        "outbox publish failed eventId={} stage=broker-confirm kind={}",
                        e.id(),
                        ex.getClass().getSimpleName());
            }
        }
    }
}
