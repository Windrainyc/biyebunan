package cn.zhixiang.platform.notification.service;

import cn.zhixiang.platform.identity.mapper.IdentityMapper;
import cn.zhixiang.platform.identity.model.Actor;
import cn.zhixiang.platform.notification.dto.AnnouncementDtos;
import cn.zhixiang.platform.notification.entity.AnnouncementEntity;
import cn.zhixiang.platform.notification.mapper.AnnouncementMapper;
import cn.zhixiang.platform.shared.dto.*;
import cn.zhixiang.platform.shared.exception.BusinessException;
import cn.zhixiang.platform.shared.mapper.WriteMapper;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.util.*;

/** 公告发布事务：快照、投递任务及outbox一同提交，已发布正文不可变。 */
@Service
public class AnnouncementService {
    private final AnnouncementMapper mapper;
    private final IdentityMapper identities;
    private final WriteMapper writes;

    /**
     * 创建用例。
     *
     * @param mapper 公告端口
     * @param identities 真实权限
     * @param writes 审计
     */
    public AnnouncementService(
            AnnouncementMapper mapper, IdentityMapper identities, WriteMapper writes) {
        this.mapper = mapper;
        this.identities = identities;
        this.writes = writes;
    }

    /**
     * 发布并快照接收者；无效ID导致整笔事务回滚，不部分发送。
     *
     * @param w 已校验输入
     * @param actor 服务端管理员
     * @return 发布详情及初始统计
     */
    @Transactional(rollbackFor = Exception.class)
    public AnnouncementDtos.View publish(AnnouncementDtos.Publish w, Actor actor) {
        require(actor, "announcements:write");
        boolean selected = "SELECTED".equals(w.audience());
        if (selected == w.recipientIds().isEmpty()
                || new HashSet<>(w.recipientIds()).size() != w.recipientIds().size())
            throw new BusinessException(
                    422, "ANNOUNCEMENT_RECIPIENT_INVALID", "请选择不重复的有效接收用户；全部发送时不要附带用户列表");
        UUID id = UUID.randomUUID();
        mapper.insert(id, w, actor.id());
        int count = mapper.snapshot(id, selected, w.recipientIds());
        if (count == 0 || (selected && count != w.recipientIds().size()))
            throw new BusinessException(422, "ANNOUNCEMENT_RECIPIENT_INVALID", "接收用户不存在或已停用，请重新选择");
        mapper.events(id);
        String body = w.body().strip();
        String summary = body.length() > 180 ? body.substring(0, 180) + "…" : body;
        mapper.tasks(id, w.title().strip(), summary);
        writes.audit(UUID.randomUUID(), actor.id(), "PUBLISH", "announcement", id);
        return view(mapper.get(id), true);
    }

    /**
     * 查询发送记录。
     *
     * @param page 页码
     * @param size 每页条数
     * @param q 标题搜索
     * @param actor 管理员
     * @return 真实分页
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResult<AnnouncementDtos.View> list(int page, int size, String q, Actor actor) {
        require(actor, "announcements:read");
        var query = new PageQuery(page, size, q, null, null);
        return new PageResult<>(
                mapper.list(query).stream().map(r -> view(r, true)).toList(),
                page,
                size,
                mapper.count(query));
    }

    /**
     * 查询正文；普通用户只读本人接收快照，不返回全体发送统计。
     *
     * @param id 公告
     * @param actor 当前身份
     * @return 授权正文
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AnnouncementDtos.View get(UUID id, Actor actor) {
        boolean manage =
                actor.platform().equals("admin")
                        && identities.permissions(actor.id()).contains("announcements:read");
        var r = mapper.get(id);
        if (r == null || (!manage && !mapper.receives(id, actor.id())))
            throw new BusinessException(404, "RESOURCE_NOT_FOUND", "公告不存在");
        return view(r, manage);
    }

    /**
     * 查询供发送选择的用户，返回脱敏信息。
     *
     * @param page 页码
     * @param size 每页条数
     * @param q 显示名或脱敏手机号搜索
     * @param platform 可选平台
     * @param actor 管理员
     * @return 接收者分页
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResult<AnnouncementDtos.Recipient> users(
            int page, int size, String q, String platform, Actor actor) {
        require(actor, "announcements:write");
        if (platform != null && !Set.of("admin", "student", "partner").contains(platform))
            throw new BusinessException(400, "INVALID_FILTER", "平台筛选无效");
        var query = new PageQuery(page, size, q, null, null);
        return new PageResult<>(
                mapper.users(query, platform).stream()
                        .map(
                                r ->
                                        new AnnouncementDtos.Recipient(
                                                r.id(),
                                                r.displayName(),
                                                r.platform(),
                                                r.phoneMasked()))
                        .toList(),
                page,
                size,
                mapper.userCount(query, platform));
    }

    private AnnouncementDtos.View view(AnnouncementEntity r, boolean manage) {
        return new AnnouncementDtos.View(
                r.id(),
                r.title(),
                r.body(),
                r.kind(),
                r.audience(),
                r.createdAt(),
                manage ? mapper.recipients(r.id()) : null,
                manage ? mapper.delivered(r.id()) : null,
                manage ? mapper.read(r.id()) : null);
    }

    private void require(Actor actor, String permission) {
        if (!actor.platform().equals("admin")
                || !identities.permissions(actor.id()).contains(permission))
            throw new BusinessException(403, "FORBIDDEN", "没有公告管理权限");
    }
}
