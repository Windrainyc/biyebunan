package cn.zhixiang.platform.operations.service;

import cn.zhixiang.platform.identity.model.Actor;
import cn.zhixiang.platform.operations.dto.ActivityDtos;
import cn.zhixiang.platform.operations.mapper.UserActivityMapper;
import cn.zhixiang.platform.shared.dto.*;
import cn.zhixiang.platform.shared.exception.BusinessException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;

/** 活动收集与管理员查询，身份和记录时间由服务端确定。 */
@Service
public class ActivityService {
    private final UserActivityMapper mapper;
    private final IpLocationService locations;

    /**
     * 初始化业务组件。
     *
     * @param mapper 活动存储
     * @param locations 离线归属地查询
     */
    public ActivityService(UserActivityMapper mapper, IpLocationService locations) {
        this.locations = locations;
        this.mapper = mapper;
    }

    /**
     * 上报当前平台的浏览活动。
     *
     * @param w 上报片段
     * @param actor 身份
     * @param ip 观测地址
     * @param platform 路径平台
     */
    @Transactional
    public void record(ActivityDtos.Write w, Actor actor, String ip, String platform) {
        if (!actor.platform().equals(platform)
                || !(w.path().equals("/" + platform) || w.path().startsWith("/" + platform + "/")))
            throw new BusinessException(403, "FORBIDDEN", "平台不匹配");
        if (mapper.recent(actor.id()) >= 60)
            throw new BusinessException(429, "ACTIVITY_LIMIT", "访问记录过于频繁");
        if (w.contentId() != null
                && (!platform.equals("student") || !mapper.readable(w.contentId(), actor.id())))
            throw new BusinessException(403, "FORBIDDEN", "知识记录不可访问");
        mapper.insert(w, actor, ip);
    }

    /**
     * 最多查询93个自然日的真实记录。
     *
     * @param from 开始日
     * @param to 结束日
     * @param user 可选用户
     * @param kind 可选类型
     * @param page 页码
     * @param size 页大小
     * @return 分页记录
     */
    @Transactional(
            readOnly = true,
            isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public PageResult<ActivityDtos.View> list(
            LocalDate from, LocalDate to, UUID user, String kind, int page, int size) {
        var q = new PageQuery(page, size, null, null, null);
        if (to.isBefore(from) || java.time.temporal.ChronoUnit.DAYS.between(from, to) > 92)
            throw new BusinessException(400, "DATE_RANGE_INVALID", "查询范围最多93天");
        if (kind != null && !Set.of("LOGIN", "VIEW", "ACTION").contains(kind))
            throw new BusinessException(400, "INVALID_FILTER", "类型无效");
        var zone = ZoneId.of("Asia/Shanghai");
        var start = from.atStartOfDay(zone).toOffsetDateTime();
        var end = to.plusDays(1).atStartOfDay(zone).toOffsetDateTime();
        return new PageResult<>(
                mapper.list(start, end, user, kind, size, q.offset()).stream()
                        .map(
                                e ->
                                        new ActivityDtos.View(
                                                e.id(),
                                                e.actorId(),
                                                e.displayName(),
                                                e.platform(),
                                                e.kind(),
                                                e.path(),
                                                e.contentId(),
                                                e.contentTitle(),
                                                e.seconds(),
                                                e.ip(),
                                                locations.locate(e.ip()),
                                                e.createdAt()))
                        .toList(),
                page,
                size,
                mapper.count(start, end, user, kind));
    }

    /**
     * 汇总指定日期范围。
     *
     * @param from 开始日期
     * @param to 结束日期
     * @param user 可选用户
     * @param kind 可选类型
     * @return 日统计
     */
    @Transactional(readOnly = true)
    public List<ActivityDtos.Daily> daily(LocalDate from, LocalDate to, UUID user, String kind) {
        if (to.isBefore(from) || java.time.temporal.ChronoUnit.DAYS.between(from, to) > 92)
            throw new BusinessException(400, "DATE_RANGE_INVALID", "查询范围最多93天");
        if (kind != null && !Set.of("LOGIN", "VIEW", "ACTION").contains(kind))
            throw new BusinessException(400, "INVALID_FILTER", "类型无效");
        var zone = ZoneId.of("Asia/Shanghai");
        return mapper.daily(
                from.atStartOfDay(zone).toOffsetDateTime(),
                to.plusDays(1).atStartOfDay(zone).toOffsetDateTime(),
                user,
                kind);
    }
}
