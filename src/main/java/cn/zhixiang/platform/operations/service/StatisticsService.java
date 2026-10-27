package cn.zhixiang.platform.operations.service;

import cn.zhixiang.platform.identity.model.Actor;
import cn.zhixiang.platform.operations.dto.StatisticsItem;
import cn.zhixiang.platform.operations.dto.StatisticsResponse;
import cn.zhixiang.platform.operations.mapper.StatisticsMapper;
import cn.zhixiang.platform.shared.exception.BusinessException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** 三端统计查询；当前归属过滤、北京时间自然日、数据库同快照聚合。 */
@Service
public class StatisticsService {
    private final StatisticsMapper mapper;
    private final cn.zhixiang.platform.identity.mapper.IdentityMapper identity;

    /**
     * 注入统计仓储。
     *
     * @param mapper 聚合查询
     * @param identity 当前账务读取权限
     */
    public StatisticsService(
            StatisticsMapper mapper, cn.zhixiang.platform.identity.mapper.IdentityMapper identity) {
        this.mapper = mapper;
        this.identity = identity;
    }

    /**
     * 返回明确口径的日聚合；中介不查询任何私聊/模型或用户活动数据。
     *
     * @param from 首日，包含
     * @param to 末日，包含，最多366天
     * @param actor 服务端身份
     * @return 同快照统计
     * @throws BusinessException 日期或平台不合法
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public StatisticsResponse get(LocalDate from, LocalDate to, Actor actor) {
        if (from == null
                || to == null
                || to.isBefore(from)
                || ChronoUnit.DAYS.between(from, to) > 365
                || from.isBefore(LocalDate.of(2000, 1, 1))
                || to.isAfter(LocalDate.now(ZoneId.of("Asia/Shanghai"))))
            throw new BusinessException(400, "INVALID_FILTER", "统计日期须为2000年至今天之间、不超过366天的有效范围");
        if (!Set.of("admin", "student", "partner").contains(actor.platform()))
            throw new BusinessException(403, "FORBIDDEN", "当前平台不能查询统计");
        var start = from.atStartOfDay(ZoneId.of("Asia/Shanghai")).toOffsetDateTime();
        var end = to.plusDays(1).atStartOfDay(ZoneId.of("Asia/Shanghai")).toOffsetDateTime();
        OffsetDateTime generated = mapper.snapshotTime();
        List<StatisticsItem> items = new ArrayList<>(mapper.business(start, end, actor));
        if (!actor.platform().equals("partner")) {
            if (actor.platform().equals("student")
                    || identity.permissions(actor.id()).contains("billing:read"))
                items.addAll(mapper.usage(start, end, actor));
            items.addAll(mapper.activeStudents(start, end, actor));
        }
        items.sort(
                Comparator.comparing(StatisticsItem::bucket).thenComparing(StatisticsItem::metric));
        return new StatisticsResponse(from, to, generated, 0, List.copyOf(items));
    }
}
