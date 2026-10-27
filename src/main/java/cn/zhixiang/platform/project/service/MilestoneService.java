package cn.zhixiang.platform.project.service;

import cn.zhixiang.platform.identity.model.Actor;
import cn.zhixiang.platform.order.mapper.OrderMapper;
import cn.zhixiang.platform.project.dto.MilestoneDtos;
import cn.zhixiang.platform.project.entity.MilestoneEntity;
import cn.zhixiang.platform.project.entity.ProjectEntity;
import cn.zhixiang.platform.project.mapper.MilestoneMapper;
import cn.zhixiang.platform.project.mapper.ProjectMapper;
import cn.zhixiang.platform.shared.dto.PageQuery;
import cn.zhixiang.platform.shared.dto.PageResult;
import cn.zhixiang.platform.shared.exception.BusinessException;
import cn.zhixiang.platform.shared.mapper.WriteMapper;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** 里程碑用例；持订单和项目锁防止并发权重超过100。 */
@Service
public class MilestoneService {
    private final MilestoneMapper mapper;
    private final ProjectMapper projects;
    private final OrderMapper orders;
    private final WriteMapper writes;

    /**
     * 初始化。
     *
     * @param mapper 里程碑端口
     * @param projects 项目端口
     * @param orders 订单端口
     * @param writes 审计事件
     */
    public MilestoneService(
            MilestoneMapper mapper,
            ProjectMapper projects,
            OrderMapper orders,
            WriteMapper writes) {
        this.mapper = mapper;
        this.projects = projects;
        this.orders = orders;
        this.writes = writes;
    }

    /**
     * 授权后分页。
     *
     * @param projectId 项目
     * @param q 查询
     * @param actor 身份
     * @return 分页
     */
    @Transactional(
            readOnly = true,
            isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public PageResult<MilestoneDtos.View> list(UUID projectId, PageQuery q, Actor actor) {
        if (projects.get(projectId, actor) == null) missing();
        return new PageResult<>(
                mapper.list(projectId, q).stream().map(this::view).toList(),
                q.page(),
                q.size(),
                mapper.count(projectId, q));
    }

    /**
     * 创建里程碑，父项目版本随进度修改递增。
     *
     * @param projectId 项目
     * @param w 输入
     * @param actor 管理员
     * @return 新里程碑
     */
    @Transactional(rollbackFor = Exception.class)
    public MilestoneDtos.View create(UUID projectId, MilestoneDtos.Write w, Actor actor) {
        var project = lockProject(projectId, actor);
        UUID id = UUID.randomUUID();
        validate(project, id, w);
        mapper.insert(projectId, id, w, actor.id());
        changed(projectId, id, actor.id(), "CREATED");
        return view(mapper.get(id));
    }

    /**
     * CAS更新里程碑。
     *
     * @param id 里程碑
     * @param version 版本
     * @param w 输入
     * @param actor 管理员
     * @return 新版本
     */
    @Transactional(rollbackFor = Exception.class)
    public MilestoneDtos.View update(UUID id, long version, MilestoneDtos.Write w, Actor actor) {
        var old = mapper.get(id);
        if (old == null) missing();
        var project = lockProject(old.projectId(), actor);
        checked(id, version);
        validate(project, id, w);
        if (mapper.update(id, version, w, actor.id()) != 1) conflict();
        changed(old.projectId(), id, actor.id(), "UPDATED");
        return view(mapper.get(id));
    }

    /**
     * 删除尚可维护项目的里程碑。
     *
     * @param id 里程碑
     * @param version 版本
     * @param actor 管理员
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(UUID id, long version, Actor actor) {
        var old = mapper.get(id);
        if (old == null) missing();
        lockProject(old.projectId(), actor);
        checked(id, version);
        if (mapper.delete(id, version) != 1) conflict();
        changed(old.projectId(), id, actor.id(), "DELETED");
    }

    private ProjectEntity lockProject(UUID id, Actor actor) {
        if (!"admin".equals(actor.platform()))
            throw new BusinessException(403, "FORBIDDEN", "仅管理员可维护里程碑");
        var p = projects.get(id, actor);
        if (p == null) missing();
        var o = orders.lock(p.orderId(), actor);
        if (o == null || !java.util.Set.of("CONFIRMED", "ACTIVE").contains(o.status()))
            throw new BusinessException(422, "ORDER_STATE_INVALID", "订单不允许维护里程碑");
        p = projects.lock(id);
        if (p.stage().equals("DONE"))
            throw new BusinessException(422, "PROJECT_FINISHED", "验收后不可修改里程碑");
        return p;
    }

    private void validate(ProjectEntity p, UUID id, MilestoneDtos.Write w) {
        if (w.dueDate().isAfter(p.deliveryDate()))
            throw new BusinessException(422, "MILESTONE_DATE_INVALID", "里程碑不能晚于项目交付日");
        if (mapper.weight(p.id(), id) + w.weight() > 100)
            throw new BusinessException(422, "MILESTONE_WEIGHT_INVALID", "里程碑权重总和不能超过100");
    }

    private void checked(UUID id, long version) {
        var r = mapper.lock(id);
        if (r == null) missing();
        if (r.version() != version) conflict();
    }

    private void changed(UUID project, UUID id, UUID actor, String action) {
        mapper.touch(project, actor);
        writes.audit(UUID.randomUUID(), actor, action, "project_milestone", id);
        writes.event(
                UUID.randomUUID(),
                project,
                "project_milestone." + action,
                "{\"id\":\"" + id + "\"}");
    }

    private void missing() {
        throw new BusinessException(404, "RESOURCE_NOT_FOUND", "资源不存在");
    }

    private void conflict() {
        throw new BusinessException(412, "VERSION_CONFLICT", "里程碑已更新");
    }

    private MilestoneDtos.View view(MilestoneEntity r) {
        return new MilestoneDtos.View(
                r.id(),
                r.version(),
                r.createdAt(),
                r.updatedAt(),
                r.name(),
                r.dueDate(),
                r.weight(),
                r.completed(),
                r.projectId());
    }
}
