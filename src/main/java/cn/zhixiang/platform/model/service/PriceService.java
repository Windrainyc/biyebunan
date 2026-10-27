package cn.zhixiang.platform.model.service;

import cn.zhixiang.platform.model.dto.ModelDtos;
import cn.zhixiang.platform.model.dto.PriceDtos;
import cn.zhixiang.platform.model.entity.PriceEntity;
import cn.zhixiang.platform.model.mapper.ModelMapper;
import cn.zhixiang.platform.model.mapper.PriceMapper;
import cn.zhixiang.platform.shared.dto.PageQuery;
import cn.zhixiang.platform.shared.dto.PageResult;
import cn.zhixiang.platform.shared.exception.BusinessException;
import cn.zhixiang.platform.shared.mapper.WriteMapper;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** 模型价格用例服务；锁定记录后执行版本检查，变更、审计和 outbox 同事务。 */
@Service
public class PriceService {
    private final ModelMapper models;
    private final PriceMapper mapper;
    private final WriteMapper writes;

    /**
     * 初始化模块组件，所需端口和配置由构造器注入。
     *
     * @param models 模型端口
     * @param mapper 数据端口
     * @param writes 审计事件端口
     */
    public PriceService(PriceMapper mapper, WriteMapper writes, ModelMapper models) {
        this.models = models;
        this.mapper = mapper;
        this.writes = writes;
    }

    /**
     * 返回过滤后的分页结果。
     *
     * @param q 查询条件
     * @return 过滤后的分页结果
     */
    @Transactional(
            readOnly = true,
            isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public PageResult<PriceDtos.View> list(PageQuery q) {
        return new PageResult<>(
                mapper.list(q).stream().map(this::view).toList(),
                q.page(),
                q.size(),
                mapper.count(q));
    }

    /**
     * 返回对外 DTO。
     *
     * @param id 标识
     * @return 对外 DTO
     * @throws BusinessException 不存在
     */
    public PriceDtos.View get(UUID id) {
        var v = mapper.get(id);
        if (v == null) throw new BusinessException(404, "RESOURCE_NOT_FOUND", "资源不存在");
        return view(v);
    }

    /**
     * 返回新资源。
     *
     * @param w 写入数据
     * @param actor 操作者
     * @return 新资源
     */
    @Transactional(rollbackFor = Exception.class)
    public PriceDtos.View create(PriceDtos.Write w, UUID actor) {
        parent(w.modelId());
        validate(w);
        UUID id = UUID.randomUUID();
        mapper.insert(id, w, actor);
        audit(id, actor, "CREATED");
        return get(id);
    }

    /**
     * 返回新版本。
     *
     * @param id 标识
     * @param version 期望版本
     * @param w 新数据
     * @param actor 操作者
     * @return 新版本
     */
    @Transactional(rollbackFor = Exception.class)
    public PriceDtos.View update(UUID id, long version, PriceDtos.Write w, UUID actor) {
        var old = checked(id, version);
        validate(w);
        if (!old.modelId().equals(w.modelId()))
            throw new BusinessException(422, "PRICE_MODEL_IMMUTABLE", "价格不能转移到另一模型");
        mutable(old);
        if (mapper.update(id, version, w, actor) != 1) conflict();
        audit(id, actor, "UPDATED");
        return get(id);
    }

    /**
     * 返回新版本或同值原版本。
     *
     * @param id 标识
     * @param version 期望版本
     * @param status 目标状态
     * @param actor 操作者
     * @return 新版本或同值原版本
     */
    @Transactional(rollbackFor = Exception.class)
    public PriceDtos.View status(UUID id, long version, ModelDtos.Status status, UUID actor) {
        var old = checked(id, version);
        if (old.status() == status) return view(old);
        if (mapper.status(id, version, status, actor) != 1) conflict();
        audit(id, actor, "STATUS_CHANGED");
        return get(id);
    }

    /**
     * 删除未被引用的资源，调用方必须先完成权限与版本检查。
     *
     * @param id 标识
     * @param version 期望版本
     * @param actor 操作者；有引用时数据库拒绝并回滚审计
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(UUID id, long version, UUID actor) {
        mutable(checked(id, version));
        if (mapper.delete(id, version) != 1) conflict();
        audit(id, actor, "DELETED");
    }

    private PriceEntity checked(UUID id, long version) {
        var current = mapper.get(id);
        if (current == null) throw new BusinessException(404, "RESOURCE_NOT_FOUND", "价格不存在");
        parent(current.modelId());
        var old = mapper.lock(id);
        if (old == null) throw new BusinessException(404, "RESOURCE_NOT_FOUND", "资源不存在");
        if (old.version() != version) conflict();
        return old;
    }

    private void conflict() {
        throw new BusinessException(412, "VERSION_CONFLICT", "数据已被修改，请刷新后重试");
    }

    private void audit(UUID id, UUID actor, String action) {
        writes.audit(UUID.randomUUID(), actor, action, "model_price", id);
        writes.event(UUID.randomUUID(), id, "model_price." + action, "{\"id\":\"" + id + "\"}");
    }

    private PriceDtos.View view(PriceEntity r) {
        return new PriceDtos.View(
                r.id(),
                r.version(),
                r.createdAt(),
                r.updatedAt(),
                r.modelId(),
                r.currency(),
                r.inputPerMillion(),
                r.outputPerMillion(),
                r.cachedInputPerMillion(),
                r.effectiveFrom(),
                r.status());
    }

    private void parent(UUID id) {
        if (models.lock(id) == null)
            throw new BusinessException(404, "RESOURCE_NOT_FOUND", "模型不存在");
    }

    private void mutable(PriceEntity r) {
        if (!r.effectiveFrom().isAfter(mapper.now()))
            throw new BusinessException(409, "PRICE_ALREADY_EFFECTIVE", "已生效价格不得覆盖或删除，请新增价格版本");
    }

    private void validate(PriceDtos.Write w) {
        if (!w.effectiveFrom().isAfter(mapper.now()))
            throw new BusinessException(422, "PRICE_BACKDATED", "新增或修改价格须指定未来生效时间");
    }
}
