package cn.zhixiang.platform.operations.service;

import cn.zhixiang.platform.identity.mapper.IdentityMapper;
import cn.zhixiang.platform.identity.model.Actor;
import cn.zhixiang.platform.operations.dto.AuditResponse;
import cn.zhixiang.platform.operations.mapper.AuditMapper;
import cn.zhixiang.platform.shared.dto.PageQuery;
import cn.zhixiang.platform.shared.dto.PageResult;
import cn.zhixiang.platform.shared.exception.BusinessException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** 管理员审计目录，仅提供追加事件的安全元数据。 */
@Service
public class AuditService {
    private final AuditMapper mapper;
    private final IdentityMapper identity;

    /**
     * 注入查询端口。
     *
     * @param mapper 审计仓储
     * @param identity 当前权限
     */
    public AuditService(AuditMapper mapper, IdentityMapper identity) {
        this.mapper = mapper;
        this.identity = identity;
    }

    /**
     * 查询同一数据库快照；不支持虚构启停状态或更新时间。
     *
     * @param q 请求分页
     * @param actor 服务端身份
     * @return 审计页
     * @throws BusinessException 平台不符或无效筛选
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResult<AuditResponse> list(PageQuery q, Actor actor) {
        if (!actor.platform().equals("admin"))
            throw new BusinessException(403, "FORBIDDEN", "仅管理员可查看审计");
        if (q.status() != null || q.sort().equals("updatedAt:desc"))
            throw new BusinessException(400, "INVALID_FILTER", "审计仅支持创建时间排序，不支持启停筛选");
        boolean chat = identity.permissions(actor.id()).contains("chat:audit");
        return new PageResult<>(mapper.list(q, chat), q.page(), q.size(), mapper.count(q, chat));
    }
}
