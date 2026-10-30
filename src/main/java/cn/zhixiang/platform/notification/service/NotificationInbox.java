package cn.zhixiang.platform.notification.service;

import cn.zhixiang.platform.operations.mapper.ActivityMapper;
import cn.zhixiang.platform.shared.exception.BusinessException;

import org.springframework.amqp.core.Message;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.json.JsonMapper;

import java.security.MessageDigest;
import java.util.*;

/** 通知消费者的事务边界；数据库提交后监听容器才可ACK。 */
@Service
public class NotificationInbox {
    private final ActivityMapper mapper;
    private final JsonMapper json;
    private final cn.zhixiang.platform.notification.mapper.NotificationMapper notifications;
    private final NotificationService service;

    /**
     * 创建消费者。
     *
     * @param mapper 幂等与投影端口
     * @param notifications 通知幂等持久化
     * @param service 通知投递用例
     * @param json JSON解析
     */
    public NotificationInbox(
            ActivityMapper mapper,
            JsonMapper json,
            cn.zhixiang.platform.notification.mapper.NotificationMapper notifications,
            NotificationService service) {
        this.mapper = mapper;
        this.notifications = notifications;
        this.service = service;
        this.json = json;
    }

    /**
     * 核对消息、去重和聚合原子提交；异常不可吞掉，否则会丢失消息。
     *
     * @param message broker消息，不记录正文
     * @return true首次处理，false精确重复
     * @throws BusinessException 消息无效、伪造或相同事件不同正文
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean consume(Message message) {
        byte[] body = message.getBody();
        if (body.length > 262144) invalid();
        UUID id, aggregate;
        String type, data, hash;
        try {
            var root = json.readTree(body);
            if (!root.isObject() || root.size() != 4) throw new IllegalArgumentException();
            id = UUID.fromString(root.get("eventId").asText());
            aggregate = UUID.fromString(root.get("aggregateId").asText());
            type = root.get("type").asText();
            data = json.writeValueAsString(root.get("data"));
            if (!id.toString().equals(message.getMessageProperties().getMessageId()))
                throw new IllegalArgumentException();
            hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (Exception e) {
            throw new BusinessException(422, "EVENT_INVALID", "事件协议不合法");
        }
        if (!mapper.matches(id, aggregate, type, data)) invalid();
        if (notifications.receiveEvent(id, hash) == 0) {
            if (!hash.equals(notifications.eventDigest(id)))
                throw new BusinessException(409, "EVENT_BODY_CONFLICT", "同一事件正文不一致");
            return false;
        }
        service.processEvent(id);
        return true;
    }

    private void invalid() {
        throw new BusinessException(422, "EVENT_INVALID", "事件与本地outbox不一致");
    }
}
