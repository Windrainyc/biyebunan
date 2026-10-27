package cn.zhixiang.platform.notification.service;

import cn.zhixiang.platform.identity.model.Actor;
import cn.zhixiang.platform.notification.dto.NotificationResponse;
import cn.zhixiang.platform.notification.entity.NotificationEntity;
import cn.zhixiang.platform.notification.mapper.NotificationMapper;
import cn.zhixiang.platform.shared.dto.PageQuery;
import cn.zhixiang.platform.shared.dto.PageResult;
import cn.zhixiang.platform.shared.exception.BusinessException;
import cn.zhixiang.platform.shared.mapper.WriteMapper;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.util.*;

/** 私有收件箱与内部事件投递；不提供客户端指定收件人的发送接口。 */
@Service
public class NotificationService {
    private final NotificationMapper mapper;
    private final cn.zhixiang.platform.notification.mapper.TemplateMapper templates;
    private final WriteMapper writes;

    /**
     * 注入持久化与审计端口。
     *
     * @param templates 模板目录
     * @param mapper 通知数据
     * @param writes 事务审计
     */
    public NotificationService(
            NotificationMapper mapper,
            WriteMapper writes,
            cn.zhixiang.platform.notification.mapper.TemplateMapper templates) {
        this.mapper = mapper;
        this.templates = templates;
        this.writes = writes;
    }

    /**
     * 三端均只能查看自己的通知，管理员没有越权读取例外。
     *
     * @param page 页码
     * @param size 每页数量
     * @param q 标题正文搜索
     * @param status READ或UNREAD，可空
     * @param sort 白名单排序
     * @param actor 服务端身份
     * @return 本人通知分页
     * @throws BusinessException 非法筛选
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResult<NotificationResponse> list(
            int page, int size, String q, String status, String sort, Actor actor) {
        if (status != null && !Set.of("READ", "UNREAD").contains(status))
            throw new BusinessException(400, "INVALID_FILTER", "通知状态仅支持READ或UNREAD");
        var query = new PageQuery(page, size, q, null, sort);
        Boolean unread = status == null ? null : status.equals("UNREAD");
        return new PageResult<>(
                mapper.list(actor.id(), query, unread).stream().map(this::view).toList(),
                page,
                size,
                mapper.count(actor.id(), query, unread));
    }

    /**
     * 单向已读天然幂等，不需要客户端CAS版本；首次状态变化记录审计。
     *
     * @param id 通知
     * @param actor 服务端身份
     * @throws BusinessException 不存在或非本人均404
     */
    @Transactional(rollbackFor = Exception.class)
    public void read(UUID id, Actor actor) {
        var row = mapper.lock(id, actor.id());
        if (row == null) throw new BusinessException(404, "RESOURCE_NOT_FOUND", "通知不存在");
        if (row.readAt() != null) return;
        mapper.markRead(id);
        writes.audit(UUID.randomUUID(), actor.id(), "READ", "notification", id);
    }

    /**
     * 内部业务事务投递安全文案，同事件同收件人去重，不实际发送短信。 必须由领域事件适配器在持有业务事务时调用；不能将未授权原文放入文案。
     *
     * @param recipient 收件人
     * @param event 唯一领域事件
     * @param title 不超过200字的安全标题
     * @param body 不超过200字的安全正文
     * @return 去重后的通知标识
     * @throws BusinessException 无效文案或事件载荷冲突
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID deliver(UUID recipient, UUID event, String title, String body) {
        if (recipient == null
                || event == null
                || title == null
                || body == null
                || title.isBlank()
                || body.isBlank()
                || title.length() > 200
                || body.length() > 200)
            throw new BusinessException(422, "NOTIFICATION_INVALID", "通知文案不合法");
        mapper.insert(UUID.randomUUID(), recipient, event, title, body);
        var row = mapper.event(recipient, event);
        if (!mapper.sameContent(recipient, event, title, body))
            throw new BusinessException(409, "NOTIFICATION_EVENT_CONFLICT", "同一通知事件的文案不可改变");
        return row.id();
    }

    /**
     * 业务事务只追加待投递记录，避免持项目锁时再获取学生账户锁。
     *
     * @param recipient 接收者快照
     * @param event 已持久化的领域事件
     * @param title 安全标题
     * @param body 安全正文
     * @throws BusinessException 文案不合法或同事件载荷冲突
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(UUID recipient, UUID event, String title, String body) {
        if (recipient == null
                || event == null
                || title == null
                || body == null
                || title.isBlank()
                || body.isBlank()
                || title.length() > 200
                || body.length() > 200)
            throw new BusinessException(422, "NOTIFICATION_INVALID", "通知文案不合法");
        mapper.enqueue(recipient, event, title, body);
        if (!mapper.sameTaskContent(recipient, event, title, body))
            throw new BusinessException(409, "NOTIFICATION_EVENT_CONFLICT", "同事件通知内容不可改变");
    }

    /**
     * 原子投递一项待处理通知；没有外部网络调用，异常回滚后可安全再次领取。
     *
     * @return 是否处理到任务
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean processOne() {
        var task = mapper.pending();
        if (task == null) return false;
        boolean missing = !mapper.recipientExists(task.recipientId());
        if (!missing) deliver(task.recipientId(), task.eventId(), task.title(), task.body());
        mapper.delivered(task.recipientId(), task.eventId(), missing);
        return true;
    }

    /**
     * 交付发布的受控模板渲染；只使用通用安全文案，不嵌入项目私密材料。 未配置时采用正式内置默认；显式禁用时记录失败而不投递。
     *
     * @param recipient 服务端接收者快照
     * @param event 领域事件标识
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueDelivery(UUID recipient, UUID event) {
        if (mapper.task(recipient, event) != null) return;
        var template = templates.byCode("DELIVERY_PUBLISHED");
        String renderer =
                template == null ? "delivery-published-v1" : template.providerTemplateId();
        String error = null;
        if (template != null
                && template.status()
                        != cn.zhixiang.platform.notification.dto.TemplateDtos.Status.ENABLED)
            error = "TEMPLATE_DISABLED";
        if (!Set.of("delivery-published-v1", "delivery-published-brief-v1").contains(renderer))
            error = "TEMPLATE_RENDERER_UNAVAILABLE";
        String title = "项目资料已更新";
        String body =
                renderer.equals("delivery-published-brief-v1")
                        ? "新资料已发布，请查看项目。"
                        : "您有新的项目资料，请前往项目页面查看。";
        mapper.enqueueTemplate(
                recipient,
                event,
                title,
                body,
                template == null ? null : template.id(),
                template == null ? null : template.version(),
                renderer,
                error);
    }

    private NotificationResponse view(NotificationEntity r) {
        return new NotificationResponse(
                r.id(), r.title(), r.body(), r.readAt() != null, r.createdAt());
    }
}
