package cn.zhixiang.platform.identity.service;

import cn.zhixiang.platform.identity.dto.PartnerStudentResponse;
import cn.zhixiang.platform.identity.mapper.PartnerStudentMapper;
import cn.zhixiang.platform.identity.model.Actor;
import cn.zhixiang.platform.shared.dto.PageQuery;
import cn.zhixiang.platform.shared.dto.PageResult;
import cn.zhixiang.platform.shared.exception.BusinessException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.util.UUID;

/** 中介学生读取用例，不允许跨中介或读取学生私聊。 */
@Service
public class PartnerStudentService {
    private final PartnerStudentMapper mapper;

    /**
     * 创建用例。
     *
     * @param mapper 范围查询
     */
    public PartnerStudentService(PartnerStudentMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 当前中介学生分页。
     *
     * @param q 过滤
     * @param actor 中介身份
     * @return 分页
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResult<PartnerStudentResponse> list(PageQuery q, Actor actor) {
        check(actor);
        return new PageResult<>(
                mapper.list(q, actor.id()), q.page(), q.size(), mapper.count(q, actor.id()));
    }

    /**
     * 当前中介学生详情，范围外统一404。
     *
     * @param id 学生
     * @param actor 中介身份
     * @return 最小资料
     */
    public PartnerStudentResponse get(UUID id, Actor actor) {
        check(actor);
        var row = mapper.get(id, actor.id());
        if (row == null) throw new BusinessException(404, "RESOURCE_NOT_FOUND", "学生不存在");
        return row;
    }

    private void check(Actor actor) {
        if (!"partner".equals(actor.platform()))
            throw new BusinessException(403, "FORBIDDEN", "仅中介可查看名下学生");
    }
}
