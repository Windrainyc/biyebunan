package cn.zhixiang.platform.model.service;

import cn.zhixiang.platform.model.dto.ModelDtos;
import cn.zhixiang.platform.model.entity.ModelEntity;
import cn.zhixiang.platform.model.mapper.ModelMapper;
import cn.zhixiang.platform.shared.dto.PageQuery;
import cn.zhixiang.platform.shared.dto.PageResult;
import cn.zhixiang.platform.shared.exception.BusinessException;
import cn.zhixiang.platform.shared.mapper.WriteMapper;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** 模型用例服务；锁定记录后执行版本检查，变更、审计和 outbox 同事务。 */
@Service
public class ModelService {
    private final ModelMapper mapper;
    private final WriteMapper writes;

    /**
     * 初始化模块组件，所需端口和配置由构造器注入。
     *
     * @param mapper 数据端口
     * @param writes 审计事件端口
     */
    public ModelService(ModelMapper mapper, WriteMapper writes) {
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
    public PageResult<ModelDtos.View> list(PageQuery q) {
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
    public ModelDtos.View get(UUID id) {
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
    public ModelDtos.View create(ModelDtos.Write w, UUID actor) {
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
    public ModelDtos.View update(UUID id, long version, ModelDtos.Write w, UUID actor) {
        validate(w);
        var old = checked(id, version);
        if (mapper.referenced(id)
                && (!old.code().equals(w.code())
                        || !old.provider().equals(w.provider())
                        || !old.endpoint().equals(w.endpoint())))
            throw new BusinessException(
                    409, "MODEL_IDENTITY_REFERENCED", "已有价格或凭证引用，不能更换模型代码、供应商或端点，请新建模型");
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
    public ModelDtos.View status(UUID id, long version, ModelDtos.Status status, UUID actor) {
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
        checked(id, version);
        if (mapper.delete(id, version) != 1) conflict();
        audit(id, actor, "DELETED");
    }

    private ModelEntity checked(UUID id, long version) {
        var old = mapper.lock(id);
        if (old == null) throw new BusinessException(404, "RESOURCE_NOT_FOUND", "资源不存在");
        if (old.version() != version) conflict();
        return old;
    }

    private void conflict() {
        throw new BusinessException(412, "VERSION_CONFLICT", "数据已被修改，请刷新后重试");
    }

    private void audit(UUID id, UUID actor, String action) {
        writes.audit(UUID.randomUUID(), actor, action, "model_definition", id);
        writes.event(
                UUID.randomUUID(), id, "model_definition." + action, "{\"id\":\"" + id + "\"}");
    }

    private ModelDtos.View view(ModelEntity r) {
        return new ModelDtos.View(
                r.id(),
                r.version(),
                r.createdAt(),
                r.updatedAt(),
                r.name(),
                r.code(),
                r.provider(),
                r.endpoint(),
                r.contextLimit(),
                r.maxOutputTokens(),
                r.status());
    }

    private void validate(ModelDtos.Write w) {
        if (w.maxOutputTokens() > w.contextLimit())
            throw new BusinessException(422, "MODEL_LIMIT_INVALID", "最大输出不能超过上下文限制");
        // 配置阶段只做语法验证，不访问供应商；执行时还必须由适配器检查网络出口白名单。
        try {
            var uri = java.net.URI.create(w.endpoint());
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || uri.getHost() == null
                    || uri.getUserInfo() != null
                    || uri.getRawQuery() != null
                    || uri.getRawFragment() != null) throw new IllegalArgumentException();
        } catch (IllegalArgumentException e) {
            throw new BusinessException(
                    422, "MODEL_ENDPOINT_INVALID", "模型端点必须为不含凭证、查询和片段的 HTTPS 地址");
        }
    }
}
