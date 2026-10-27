package cn.zhixiang.platform.billing.service;

import cn.zhixiang.platform.billing.dto.UsageQuery;
import cn.zhixiang.platform.billing.dto.UsageResponse;
import cn.zhixiang.platform.billing.mapper.UsageMapper;
import cn.zhixiang.platform.identity.model.Actor;
import cn.zhixiang.platform.shared.dto.PageResult;
import cn.zhixiang.platform.shared.exception.BusinessException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.util.UUID;

/** 本人词元账单；仅投影数据库确认的用量，不使用Redis推算费用。 */
@Service
public class UsageService {
    private final UsageMapper mapper;

    /**
     * 创建用例。
     *
     * @param mapper 用量查询
     */
    public UsageService(UsageMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 查询本人所有历史周期用量。
     *
     * @param q 分页过滤
     * @param actor 服务端学生
     * @return 同一数据库快照的分页
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResult<UsageResponse> list(UsageQuery q, Actor actor) {
        if (!"student".equals(actor.platform()))
            throw new BusinessException(403, "FORBIDDEN", "仅学生可查看本人用量");
        return new PageResult<>(
                mapper.list(q, actor.id(), false),
                q.page(),
                q.size(),
                mapper.count(q, actor.id(), false));
    }

    /**
     * 管理员只读账单，不读取会话正文或引用资料。
     *
     * @param q 分页过滤
     * @param student 可选学生过滤
     * @param actor 管理员身份，入口另校验billing:read
     * @return 账务分页
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResult<UsageResponse> adminList(UsageQuery q, UUID student, Actor actor) {
        if (!"admin".equals(actor.platform()))
            throw new BusinessException(403, "FORBIDDEN", "仅管理员可查询账单");
        return new PageResult<>(
                mapper.list(q, student, true), q.page(), q.size(), mapper.count(q, student, true));
    }
}
