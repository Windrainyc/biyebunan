package cn.zhixiang.platform.operations.service;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.UUID;

/** 有界定位指定死信，提交后ACK；其他消息及失败消息始终重入原队列。 */
@Service
public class DeadLetterRecovery {
    private final RabbitTemplate rabbit;
    private final DeadLetterReplayService replay;

    /**
     * 创建broker恢复协调器，外部IO不持有数据库事务。
     *
     * @param rabbit 专用vhost连接
     * @param replay 短数据库事务
     */
    public DeadLetterRecovery(RabbitTemplate rabbit, DeadLetterReplayService replay) {
        this.rabbit = rabbit;
        this.replay = replay;
    }

    /**
     * 最多读取100条定位指定事件；未找到不丢弃其他消息。ACK失败可能再次恢复，由inbox去重。
     *
     * @param queue 受控zx命名队列，CLI仅允许统计或通知死信队列，测试使用隔离队列
     * @param event 显式指定事件，不批量盲目重放
     * @param token 有效管理员访问令牌
     * @param reason 操作原因
     * @return 是否找到并成功提交目标事件
     * @throws RuntimeException broker、权限或业务失败，目标保持可恢复
     */
    public boolean recover(String queue, UUID event, String token, String reason) {
        if (!queue.startsWith("zx.") || queue.length() > 200)
            throw new IllegalArgumentException("恢复队列不合法");
        return Boolean.TRUE.equals(
                rabbit.execute(
                        channel -> {
                            var pending = new ArrayList<Long>();
                            try {
                                for (int i = 0; i < 100; i++) {
                                    var delivery = channel.basicGet(queue, false);
                                    if (delivery == null) return false;
                                    long tag = delivery.getEnvelope().getDeliveryTag();
                                    pending.add(tag);
                                    if (!event.toString()
                                            .equals(delivery.getProps().getMessageId())) continue;
                                    var props =
                                            new org.springframework.amqp.core.MessageProperties();
                                    props.setMessageId(delivery.getProps().getMessageId());
                                    var message = new Message(delivery.getBody(), props);
                                    if (queue.equals("zx.notifications.dead"))
                                        replay.replayNotification(message, token, reason);
                                    else replay.replay(message, token, reason);
                                    channel.basicAck(tag, false);
                                    pending.remove(Long.valueOf(tag));
                                    return true;
                                }
                                return false;
                            } finally {
                                // 通道中断时broker会自动重入未ACK消息；不能转为自动确认以掩盖失败。
                                for (long tag : pending)
                                    if (channel.isOpen()) channel.basicNack(tag, false, true);
                            }
                        }));
    }
}
