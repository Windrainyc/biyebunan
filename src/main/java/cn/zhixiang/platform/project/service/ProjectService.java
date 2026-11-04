package cn.zhixiang.platform.project.service;

import cn.zhixiang.platform.identity.model.Actor;
import cn.zhixiang.platform.order.mapper.OrderMapper;
import cn.zhixiang.platform.project.dto.MilestoneDtos;
import cn.zhixiang.platform.project.dto.ProjectDtos;
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

/** 项目主体用例；订单先于项目锁定，确保订单状态变化与项目写入串行。 */
@Service
public class ProjectService {
    private final MilestoneMapper milestones;
    private final ProjectMapper mapper;
    private final OrderMapper orders;
    private final WriteMapper writes;

    /**
     * 创建用例。
     *
     * @param milestones 里程碑端口
     * @param mapper 项目端口
     * @param orders 订单端口
     * @param writes 审计事件
     */
    public ProjectService(
            ProjectMapper mapper,
            OrderMapper orders,
            WriteMapper writes,
            MilestoneMapper milestones) {
        this.milestones = milestones;
        this.mapper = mapper;
        this.orders = orders;
        this.writes = writes;
    }

    /**
     * 读取当前身份可见分页。
     *
     * @param q 查询
     * @param actor 身份
     * @return 分页
     */
    @Transactional(
            readOnly = true,
            isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public PageResult<ProjectDtos.View> list(ProjectDtos.Query q, Actor actor) {
        if ("partner".equals(actor.platform()))
            throw new BusinessException(403, "FORBIDDEN", "中介只能读取项目进度摘要");
        return new PageResult<>(
                mapper.list(q, actor).stream().map(this::view).toList(),
                q.page(),
                q.size(),
                mapper.count(q, actor));
    }

    /**
     * 读取项目，禁止中介通过本用例读取需求正文。
     *
     * @param id 项目
     * @param actor 身份
     * @return 项目
     */
    public ProjectDtos.View get(UUID id, Actor actor) {
        if ("partner".equals(actor.platform()))
            throw new BusinessException(403, "FORBIDDEN", "中介只能读取项目进度摘要");
        var r = mapper.get(id, actor);
        if (r == null) missing();
        return view(r);
    }

    /**
     * 一个已确认订单最多创建一个项目。
     *
     * @param w 输入
     * @param actor 管理员
     * @return 新项目
     */
    @Transactional(rollbackFor = Exception.class)
    public ProjectDtos.View create(ProjectDtos.Write w, Actor actor) {
        admin(actor);
        order(w.orderId(), actor);
        UUID id = UUID.randomUUID();
        mapper.insert(id, w, actor.id());
        requirements(id, w.requirementAssetIds());
        audit(id, actor.id(), "CREATED");
        return get(id, actor);
    }

    /**
     * 修改题目、需求和日期，不能换订单。
     *
     * @param id 项目
     * @param version 并发版本
     * @param w 输入
     * @param actor 管理员
     * @return 新版本
     */
    @Transactional(rollbackFor = Exception.class)
    public ProjectDtos.View update(UUID id, long version, ProjectDtos.Write w, Actor actor) {
        admin(actor);
        var old = mapper.get(id, actor);
        if (old == null) missing();
        if (!old.orderId().equals(w.orderId()))
            throw new BusinessException(422, "PROJECT_ORDER_IMMUTABLE", "项目不能转移到另一订单");
        order(old.orderId(), actor);
        var current = mapper.lock(id);
        if (current.version() != version)
            throw new BusinessException(412, "VERSION_CONFLICT", "项目已更新");
        if (current.stage().equals("DONE"))
            throw new BusinessException(422, "PROJECT_FINISHED", "已验收项目不可修改主体");
        if (mapper.update(id, version, w, actor.id()) != 1)
            throw new BusinessException(412, "VERSION_CONFLICT", "项目已更新");
        requirements(id, w.requirementAssetIds());
        audit(id, actor.id(), "UPDATED");
        return get(id, actor);
    }

    private void requirements(UUID project, java.util.List<UUID> ids) {
        if (ids == null) return;
        var unique = ids.stream().distinct().sorted().toList();
        for (UUID id : unique) {
            if (mapper.lockRequirementAsset(id) == null)
                throw new BusinessException(422, "REQUIREMENT_ASSET_INVALID", "需求附件必须为已扫描通过的素材");
        }
        mapper.clearRequirements(project);
        unique.forEach(id -> mapper.requirement(project, id));
    }

    private void order(UUID id, Actor actor) {
        var r = orders.lock(id, actor);
        if (r == null) missing();
        if (!java.util.Set.of("CONFIRMED", "ACTIVE").contains(r.status()))
            throw new BusinessException(422, "ORDER_STATE_INVALID", "项目只允许在已确认或进行中订单下维护");
    }

    private void admin(Actor actor) {
        if (!"admin".equals(actor.platform()))
            throw new BusinessException(403, "FORBIDDEN", "仅管理员可维护项目");
    }

    private void missing() {
        throw new BusinessException(404, "RESOURCE_NOT_FOUND", "资源不存在");
    }

    private void audit(UUID id, UUID actor, String action) {
        writes.audit(UUID.randomUUID(), actor, action, "software_project", id);
        writes.event(
                UUID.randomUUID(), id, "software_project." + action, "{\"id\":\"" + id + "\"}");
    }

    private ProjectDtos.View view(ProjectEntity r) {
        return new ProjectDtos.View(
                r.id(),
                r.version(),
                r.createdAt(),
                r.updatedAt(),
                r.orderId(),
                r.title(),
                r.requirements(),
                r.deliveryDate(),
                r.stage(),
                r.progress(),
                mapper.requirementAssets(r.id()));
    }

    /**
     * 中介范围内的分页摘要。
     *
     * @param q 查询
     * @param actor 中介身份
     * @return 不含需求或文件地址的分页
     */
    @Transactional(
            readOnly = true,
            isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public PageResult<ProjectDtos.Progress> partnerList(ProjectDtos.Query q, Actor actor) {
        partner(actor);
        return new PageResult<>(
                mapper.list(q, actor).stream().map(this::progress).toList(),
                q.page(),
                q.size(),
                mapper.count(q, actor));
    }

    /**
     * 中介只读进度详情。
     *
     * @param id 项目
     * @param actor 身份
     * @return 范围内进度
     */
    @Transactional(
            readOnly = true,
            isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public ProjectDtos.Progress partnerProgress(UUID id, Actor actor) {
        partner(actor);
        var p = mapper.get(id, actor);
        if (p == null) missing();
        return progress(p);
    }

    private void partner(Actor actor) {
        if (!"partner".equals(actor.platform()))
            throw new BusinessException(403, "FORBIDDEN", "仅中介平台可读此摘要");
    }

    private ProjectDtos.Progress progress(ProjectEntity p) {
        var list =
                milestones.list(p.id(), new PageQuery(1, 100, null, null, "createdAt:asc")).stream()
                        .map(
                                r ->
                                        new MilestoneDtos.View(
                                                r.id(),
                                                r.version(),
                                                r.createdAt(),
                                                r.updatedAt(),
                                                r.name(),
                                                r.dueDate(),
                                                r.weight(),
                                                r.completed(),
                                                r.projectId()))
                        .toList();
        return new ProjectDtos.Progress(
                p.id(), p.title(), p.stage(), p.progress(), p.deliveryDate(), list);
    }
}
