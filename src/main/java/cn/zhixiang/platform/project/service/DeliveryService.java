package cn.zhixiang.platform.project.service;

import cn.zhixiang.platform.asset.mapper.AssetMapper;
import cn.zhixiang.platform.identity.model.Actor;
import cn.zhixiang.platform.order.mapper.OrderMapper;
import cn.zhixiang.platform.project.dto.DeliveryDtos;
import cn.zhixiang.platform.project.entity.DeliveryEntity;
import cn.zhixiang.platform.project.mapper.DeliveryMapper;
import cn.zhixiang.platform.project.mapper.ProjectMapper;
import cn.zhixiang.platform.shared.dto.PageResult;
import cn.zhixiang.platform.shared.exception.BusinessException;
import cn.zhixiang.platform.shared.mapper.WriteMapper;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/** 交付版本用例：订单→项目→资产锁顺序，内容不可覆盖，发布替换原子完成。 */
@Service
public class DeliveryService {
    private final cn.zhixiang.platform.notification.service.NotificationService notifications;
    private final DeliveryMapper mapper;
    private final ProjectMapper projects;
    private final OrderMapper orders;
    private final AssetMapper assets;
    private final WriteMapper writes;

    /**
     * 创建用例。
     *
     * @param notifications 私有站内通知
     * @param mapper 交付端口
     * @param projects 项目端口
     * @param orders 订单端口
     * @param assets 资产端口
     * @param writes 审计事件
     */
    public DeliveryService(
            cn.zhixiang.platform.notification.service.NotificationService notifications,
            DeliveryMapper mapper,
            ProjectMapper projects,
            OrderMapper orders,
            AssetMapper assets,
            WriteMapper writes) {
        this.notifications = notifications;
        this.mapper = mapper;
        this.projects = projects;
        this.orders = orders;
        this.assets = assets;
        this.writes = writes;
    }

    /**
     * 授权过滤后分页。
     *
     * @param id 项目
     * @param q 条件
     * @param actor 身份
     * @return 分页
     */
    @Transactional(
            readOnly = true,
            isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public PageResult<DeliveryDtos.View> list(UUID id, DeliveryDtos.Query q, Actor actor) {
        if (!Set.of("admin", "student").contains(actor.platform())
                || projects.get(id, actor) == null) missing();
        boolean student = actor.platform().equals("student");
        return new PageResult<>(
                mapper.list(id, q, student).stream().map(this::view).toList(),
                q.page(),
                q.size(),
                mapper.count(id, q, student));
    }

    /**
     * 创建独立内容版本。
     *
     * @param project 项目
     * @param w 内容
     * @param actor 管理员
     * @return 新交付
     */
    @Transactional(rollbackFor = Exception.class)
    public DeliveryDtos.View create(UUID project, DeliveryDtos.Write w, Actor actor) {
        lock(project, actor, false);
        validAsset(w.assetId(), project);
        UUID id = UUID.randomUUID();
        mapper.insert(id, project, w, actor.id());
        audit(id, actor.id(), "CREATED", w.note());
        return view(mapper.get(id));
    }

    /**
     * 发布或撤回；已替代版本不得重新发布，撤回不会自动回退到旧版。
     *
     * @param id 交付
     * @param version 预期版本
     * @param publish 是否发布
     * @param reason 操作依据
     * @param actor 管理员
     * @return 新状态
     */
    @Transactional(rollbackFor = Exception.class)
    public DeliveryDtos.View change(
            UUID id, long version, boolean publish, String reason, Actor actor) {
        var old = mapper.get(id);
        if (old == null) missing();
        lock(old.projectId(), actor, !publish);
        var row = mapper.get(id);
        if (row.version() != version)
            throw new BusinessException(412, "VERSION_CONFLICT", "交付状态已更新");
        if (publish) {
            if (!row.state().equals("READY")
                    || mapper.newer(row.projectId(), row.kind(), row.versionNumber())) invalid();
            validAsset(row.assetId(), row.projectId());
            mapper.supersede(row.projectId(), row.kind(), actor.id());
            mapper.state(id, "PUBLISHED", actor.id());
        } else {
            if (!Set.of("READY", "PUBLISHED").contains(row.state())) invalid();
            mapper.state(id, "WITHDRAWN", actor.id());
        }
        UUID event = audit(id, actor.id(), publish ? "PUBLISHED" : "WITHDRAWN", reason);
        if (publish) {
            var p = projects.get(row.projectId(), actor);
            var o = orders.get(p.orderId(), actor);
            if (o.studentId() != null) notifications.enqueueDelivery(o.studentId(), event);
        }
        return view(mapper.get(id));
    }

    private void lock(UUID id, Actor actor, boolean withdrawal) {
        if (!actor.platform().equals("admin")) missing();
        var p = projects.get(id, actor);
        if (p == null) missing();
        var o = orders.lock(p.orderId(), actor);
        p = projects.lock(id);
        if (!withdrawal
                && (!Set.of("CONFIRMED", "ACTIVE").contains(o.status())
                        || p.stage().equals("DONE")))
            throw new BusinessException(422, "PROJECT_READ_ONLY", "当前项目不允许新增或发布交付");
    }

    private void validAsset(UUID id, UUID project) {
        var a = assets.lock(id);
        if (a == null
                || a.deletedAt() != null
                || !project.equals(a.projectId())
                || !a.state().equals("READY"))
            throw new BusinessException(422, "DELIVERY_ASSET_INVALID", "交付文件须属于当前项目且已通过扫描");
    }

    private UUID audit(UUID id, UUID actor, String action, String reason) {
        mapper.event(UUID.randomUUID(), id, action, reason, actor);
        writes.audit(UUID.randomUUID(), actor, action, "project_delivery", id);
        UUID event = UUID.randomUUID();
        writes.event(event, id, "delivery." + action, "{\"id\":\"" + id + "\"}");
        return event;
    }

    private void missing() {
        throw new BusinessException(404, "RESOURCE_NOT_FOUND", "交付或项目不存在");
    }

    private void invalid() {
        throw new BusinessException(422, "DELIVERY_STATE_INVALID", "当前交付状态不允许此操作");
    }

    private DeliveryDtos.View view(DeliveryEntity r) {
        return new DeliveryDtos.View(
                r.id(),
                r.version(),
                r.createdAt(),
                r.updatedAt(),
                r.kind(),
                r.assetId(),
                r.note(),
                r.projectId(),
                r.versionNumber(),
                r.state());
    }
}
