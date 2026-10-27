package cn.zhixiang.platform.chat.service;

import cn.zhixiang.platform.chat.dto.ConversationDtos;
import cn.zhixiang.platform.chat.entity.ConversationEntity;
import cn.zhixiang.platform.chat.mapper.ConversationMapper;
import cn.zhixiang.platform.identity.model.Actor;
import cn.zhixiang.platform.order.mapper.OrderMapper;
import cn.zhixiang.platform.project.mapper.ProjectMapper;
import cn.zhixiang.platform.shared.dto.PageResult;
import cn.zhixiang.platform.shared.exception.BusinessException;
import cn.zhixiang.platform.shared.mapper.WriteMapper;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/** 会话用例；项目必须属于当前学生，中介不能通过项目关系读取私聊。 */
@Service
public class ConversationService {
    private final ConversationMapper mapper;
    private final ProjectMapper projects;
    private final OrderMapper orders;
    private final WriteMapper writes;

    /**
     * 构造用例。
     *
     * @param mapper 会话端口
     * @param projects 项目归属
     * @param orders 订单锁
     * @param writes 审计事件
     */
    public ConversationService(
            ConversationMapper mapper,
            ProjectMapper projects,
            OrderMapper orders,
            WriteMapper writes) {
        this.mapper = mapper;
        this.projects = projects;
        this.orders = orders;
        this.writes = writes;
    }

    /**
     * 授权分页。
     *
     * @param q 查询
     * @param actor 学生
     * @return 分页
     */
    @Transactional(
            readOnly = true,
            isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public PageResult<ConversationDtos.View> list(ConversationDtos.Query q, Actor actor) {
        student(actor);
        return new PageResult<>(
                mapper.list(q, actor.id()).stream().map(this::view).toList(),
                q.page(),
                q.size(),
                mapper.count(q, actor.id()));
    }

    /**
     * 授权详情。
     *
     * @param id ID
     * @param actor 学生
     * @return 会话
     */
    public ConversationDtos.View get(UUID id, Actor actor) {
        student(actor);
        var row = mapper.get(id, actor.id());
        if (row == null) missing();
        return view(row);
    }

    /**
     * 创建会话，订单锁阻止并发归属调整。
     *
     * @param w 输入
     * @param actor 学生
     * @return 会话
     */
    @Transactional(rollbackFor = Exception.class)
    public ConversationDtos.View create(ConversationDtos.Write w, Actor actor) {
        student(actor);
        lockOrder(w.projectId(), actor);
        UUID id = UUID.randomUUID();
        mapper.insert(id, w, actor.id());
        audit(id, actor.id(), "CREATED");
        return get(id, actor);
    }

    /**
     * 隐藏本人会话，不删除审计与账务。
     *
     * @param id ID
     * @param version 预期版本
     * @param actor 学生
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(UUID id, long version, Actor actor) {
        student(actor);
        var r = mapper.get(id, actor.id());
        if (r == null) missing();
        lockOrder(r.projectId(), actor);
        r = mapper.lock(id, actor.id());
        if (r == null) missing();
        if (r.version() != version || mapper.delete(id, version) != 1)
            throw new BusinessException(412, "VERSION_CONFLICT", "会话已更新");
        audit(id, actor.id(), "HIDDEN");
    }

    private void lockOrder(UUID project, Actor actor) {
        var p = projects.get(project, actor);
        if (p == null) missing();
        var o = orders.lock(p.orderId(), actor);
        if (o == null
                || !Set.of("CONFIRMED", "ACTIVE", "COMPLETED", "ARCHIVED").contains(o.status()))
            missing();
    }

    private void student(Actor actor) {
        if (!actor.platform().equals("student"))
            throw new BusinessException(403, "FORBIDDEN", "仅学生可访问本人会话");
    }

    private void missing() {
        throw new BusinessException(404, "RESOURCE_NOT_FOUND", "会话或项目不存在");
    }

    private void audit(UUID id, UUID actor, String action) {
        writes.audit(UUID.randomUUID(), actor, action, "conversation", id);
        writes.event(UUID.randomUUID(), id, "conversation." + action, "{\"id\":\"" + id + "\"}");
    }

    private ConversationDtos.View view(ConversationEntity r) {
        return new ConversationDtos.View(
                r.id(),
                r.version(),
                r.createdAt(),
                r.updatedAt(),
                r.projectId(),
                r.title(),
                r.studentId());
    }
}
