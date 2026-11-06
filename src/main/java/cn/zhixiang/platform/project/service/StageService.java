package cn.zhixiang.platform.project.service;

import cn.zhixiang.platform.identity.model.Actor;
import cn.zhixiang.platform.order.mapper.OrderMapper;
import cn.zhixiang.platform.project.dto.ProjectDtos;
import cn.zhixiang.platform.project.dto.StageDtos;
import cn.zhixiang.platform.project.entity.ProjectEntity;
import cn.zhixiang.platform.project.mapper.ProjectMapper;
import cn.zhixiang.platform.project.mapper.StageMapper;
import cn.zhixiang.platform.shared.exception.BusinessException;
import cn.zhixiang.platform.shared.mapper.WriteMapper;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/** 项目阶段状态机；顺序前进、原阶段恢复、学生验收独立授权。 */
@Service
public class StageService {
    private static final List<String> FLOW =
            List.of(
                    "PENDING",
                    "REQUIREMENTS",
                    "PROPOSAL",
                    "DEVELOPMENT",
                    "DOCUMENTATION",
                    "ACCEPTANCE");
    private final StageMapper mapper;
    private final ProjectMapper projects;
    private final OrderMapper orders;
    private final ProjectService service;
    private final WriteMapper writes;
    private final cn.zhixiang.platform.notification.service.NotificationService notifications;

    /**
     * 创建状态机。
     *
     * @param mapper 阶段端口
     * @param projects 项目端口
     * @param orders 订单端口
     * @param service 项目响应
     * @param notifications 事务内通知快照
     * @param writes 审计事件
     */
    public StageService(
            StageMapper mapper,
            ProjectMapper projects,
            OrderMapper orders,
            ProjectService service,
            cn.zhixiang.platform.notification.service.NotificationService notifications,
            WriteMapper writes) {
        this.mapper = mapper;
        this.projects = projects;
        this.orders = orders;
        this.service = service;
        this.writes = writes;
        this.notifications = notifications;
    }

    /**
     * 管理员按顺序推进，可暂停并仅恢复到暂停前阶段。
     *
     * @param id 项目
     * @param version 版本
     * @param w 目标阶段和原因
     * @param actor 管理员
     * @return 项目
     */
    @Transactional(rollbackFor = Exception.class)
    public ProjectDtos.View change(UUID id, long version, StageDtos.Change w, Actor actor) {
        if (!actor.platform().equals("admin")) forbidden();
        var p = lock(id, version, actor);
        String target = w.stage(), paused = null;
        if (p.stage().equals("DONE") || target.equals("DONE") || p.stage().equals(target))
            invalid();
        if (p.stage().equals("PAUSED")) {
            if (!target.equals(mapper.pausedFrom(id))) invalid();
        } else if (target.equals("PAUSED")) paused = p.stage();
        else {
            int n = FLOW.indexOf(p.stage());
            if (n < 0 || n + 1 >= FLOW.size() || !FLOW.get(n + 1).equals(target)) invalid();
        }
        if (target.equals("ACCEPTANCE")) ready(p);
        return save(
                p,
                target,
                paused,
                (w.reason() == null || w.reason().isBlank() ? "阶段操作" : w.reason().trim()),
                null,
                actor);
    }

    /**
     * 本人项目验收；拒绝回到开发阶段并保留具体原因。
     *
     * @param id 项目
     * @param version 版本
     * @param w 验收决定
     * @param actor 学生
     * @return 项目
     */
    @Transactional(rollbackFor = Exception.class)
    public ProjectDtos.View accept(UUID id, long version, StageDtos.Acceptance w, Actor actor) {
        if (!actor.platform().equals("student")) forbidden();
        var p = lock(id, version, actor);
        if (!p.stage().equals("ACCEPTANCE")) invalid();
        boolean accepted = w.decision().equals("ACCEPT");
        if (accepted) ready(p);
        return save(
                p,
                accepted ? "DONE" : "DEVELOPMENT",
                null,
                (w.reason() == null || w.reason().isBlank() ? "阶段操作" : w.reason().trim()),
                w.decision(),
                actor);
    }

    private ProjectEntity lock(UUID id, long version, Actor actor) {
        var p = projects.get(id, actor);
        if (p == null) throw new BusinessException(404, "RESOURCE_NOT_FOUND", "项目不存在");
        var o = orders.lock(p.orderId(), actor);
        if (o == null || !o.status().equals("ACTIVE"))
            throw new BusinessException(422, "ORDER_STATE_INVALID", "须先激活已绑定且收款的订单");
        p = projects.lock(id);
        if (p.version() != version) throw new BusinessException(412, "VERSION_CONFLICT", "项目已更新");
        return p;
    }

    private void ready(ProjectEntity p) {
        if (!mapper.deliverable(p.id()))
            throw new BusinessException(422, "PROJECT_NOT_DELIVERABLE", "须先发布源码与论文模板");
    }

    private ProjectDtos.View save(
            ProjectEntity p,
            String target,
            String paused,
            String reason,
            String decision,
            Actor actor) {
        if (mapper.change(p.id(), p.version(), target, paused, actor.id()) != 1)
            throw new BusinessException(412, "VERSION_CONFLICT", "项目已更新");
        mapper.event(UUID.randomUUID(), p.id(), p.stage(), target, reason, actor.id(), decision);
        writes.audit(UUID.randomUUID(), actor.id(), "STAGE_CHANGED", "project", p.id());
        UUID event = UUID.randomUUID();
        writes.event(event, p.id(), "project.STAGE_CHANGED", "{\"id\":\"" + p.id() + "\"}");
        if (actor.platform().equals("student"))
            notifications.enqueueAdministrators(
                    event,
                    "get_admin_projects",
                    "学生提交了验收结果",
                    "ACCEPT".equals(decision) ? "学生已确认验收，请查看项目记录。" : "学生要求继续修改，请查看项目验收记录。");
        var order = orders.get(p.orderId(), actor);
        if (order.studentId() != null)
            notifications.enqueue(order.studentId(), event, "项目进度已更新", "你的项目阶段已更新，请进入项目空间查看最新进展。");
        return service.get(p.id(), actor);
    }

    private void invalid() {
        throw new BusinessException(422, "PROJECT_STAGE_INVALID", "不允许此阶段迁移");
    }

    private void forbidden() {
        throw new BusinessException(403, "FORBIDDEN", "平台角色不允许此操作");
    }
}
