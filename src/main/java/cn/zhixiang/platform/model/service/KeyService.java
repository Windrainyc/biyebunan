package cn.zhixiang.platform.model.service;

import cn.zhixiang.platform.model.dto.KeyDtos;
import cn.zhixiang.platform.model.dto.ModelDtos;
import cn.zhixiang.platform.model.entity.KeyEntity;
import cn.zhixiang.platform.model.mapper.KeyMapper;
import cn.zhixiang.platform.model.mapper.ModelMapper;
import cn.zhixiang.platform.shared.dto.PageQuery;
import cn.zhixiang.platform.shared.dto.PageResult;
import cn.zhixiang.platform.shared.exception.BusinessException;
import cn.zhixiang.platform.shared.mapper.WriteMapper;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** 模型凭证用例服务；锁定记录后执行版本检查，变更、审计和 outbox 同事务。 */
@Service
public class KeyService {
    private final ModelMapper models;
    private final cn.zhixiang.platform.identity.service.SecretVault vault;
    private final KeyMapper mapper;
    private final WriteMapper writes;

    /**
     * 初始化模块组件，所需端口和配置由构造器注入。
     *
     * @param models 模型端口
     * @param vault 加密秘密仓库
     * @param mapper 数据端口
     * @param writes 审计事件端口
     */
    public KeyService(
            KeyMapper mapper,
            WriteMapper writes,
            ModelMapper models,
            cn.zhixiang.platform.identity.service.SecretVault vault) {
        this.models = models;
        this.vault = vault;
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
    public PageResult<KeyDtos.View> list(PageQuery q) {
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
    public KeyDtos.View get(UUID id) {
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
    public KeyDtos.View create(KeyDtos.Write w, UUID actor) {
        parent(w.modelId());
        UUID id = UUID.randomUUID();
        mapper.insert(id, w, actor, vault.put(w.secretValue()), mask(w.secretValue()));
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
    public KeyDtos.View rotate(UUID id, long version, KeyDtos.Rotate w, UUID actor) {
        var old = checked(id, version);
        if (w.reason().contains(w.secretValue()) || w.reason().contains(vault.get(old.secretRef())))
            throw new BusinessException(422, "SECRET_IN_REASON", "轮换原因不得包含密钥");
        UUID ref = vault.put(w.secretValue());
        if (mapper.rotate(id, version, ref, mask(w.secretValue()), actor) != 1) conflict();
        mapper.erase(old.secretRef());
        writes.auditReason(UUID.randomUUID(), actor, "ROTATED", "model_key", id, w.reason());
        writes.event(UUID.randomUUID(), id, "model_key.ROTATED", "{\"id\":\"" + id + "\"}");
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
    public KeyDtos.View status(UUID id, long version, ModelDtos.Status status, UUID actor) {
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
        var old = checked(id, version);
        if (old.status() != ModelDtos.Status.DISABLED)
            throw new BusinessException(409, "KEY_MUST_BE_DISABLED", "请先停用凭证再删除");
        if (mapper.delete(id, version) != 1) conflict();
        mapper.erase(old.secretRef());
        audit(id, actor, "DELETED");
    }

    private KeyEntity checked(UUID id, long version) {
        var current = mapper.get(id);
        if (current == null) throw new BusinessException(404, "RESOURCE_NOT_FOUND", "凭证不存在");
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
        writes.audit(UUID.randomUUID(), actor, action, "model_key", id);
        writes.event(UUID.randomUUID(), id, "model_key." + action, "{\"id\":\"" + id + "\"}");
    }

    private KeyDtos.View view(KeyEntity r) {
        return new KeyDtos.View(
                r.id(),
                r.version(),
                r.createdAt(),
                r.updatedAt(),
                r.name(),
                r.modelId(),
                r.maskedSuffix(),
                r.status(),
                r.rotatedAt());
    }

    private void parent(UUID id) {
        if (models.lock(id) == null)
            throw new BusinessException(404, "RESOURCE_NOT_FOUND", "模型不存在");
    }

    private String mask(String value) {
        return value.length() < 8 ? "****" : "****" + value.substring(value.length() - 4);
    }
}
