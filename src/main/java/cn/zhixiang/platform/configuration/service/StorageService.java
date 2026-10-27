package cn.zhixiang.platform.configuration.service;

import cn.zhixiang.platform.configuration.dto.StorageDtos;
import cn.zhixiang.platform.configuration.entity.StorageEntity;
import cn.zhixiang.platform.configuration.mapper.StorageMapper;
import cn.zhixiang.platform.shared.dto.PageQuery;
import cn.zhixiang.platform.shared.dto.PageResult;
import cn.zhixiang.platform.shared.exception.BusinessException;
import cn.zhixiang.platform.shared.mapper.WriteMapper;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** 配置用例服务；锁定记录后执行版本检查，变更、审计和 outbox 同事务。 */
@Service
public class StorageService {
    private final StorageMapper mapper;
    private final WriteMapper writes;

    /**
     * 初始化模块组件，所需端口和配置由构造器注入。
     *
     * @param mapper 数据端口
     * @param writes 审计事件端口
     */
    public StorageService(StorageMapper mapper, WriteMapper writes) {
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
    public PageResult<StorageDtos.View> list(PageQuery q) {
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
    public StorageDtos.View get(UUID id) {
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
    public StorageDtos.View create(StorageDtos.Write w, UUID actor) {
        w = normalize(w);
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
    public StorageDtos.View update(UUID id, long version, StorageDtos.Write w, UUID actor) {
        w = normalize(w);
        var old = checked(id, version);
        if ((old.status() == StorageDtos.Status.ENABLED || mapper.referenced(id))
                && (!old.endpoint().equals(w.endpoint())
                        || !old.bucket().equals(w.bucket())
                        || old.provider() != w.provider()
                        || !old.region().equals(w.region())
                        || !old.credentialRef().equals(w.credentialRef())))
            throw new BusinessException(409, "CONFIG_MUST_BE_DISABLED", "有资产引用不能更换存储目标；未引用配置须先停用");
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
    public StorageDtos.View status(UUID id, long version, StorageDtos.Status status, UUID actor) {
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
        if (old.status() != StorageDtos.Status.DISABLED)
            throw new BusinessException(409, "CONFIG_MUST_BE_DISABLED", "请先停用配置再删除");
        if (mapper.delete(id, version) != 1) conflict();
        audit(id, actor, "DELETED");
    }

    private StorageEntity checked(UUID id, long version) {
        var old = mapper.lock(id);
        if (old == null) throw new BusinessException(404, "RESOURCE_NOT_FOUND", "资源不存在");
        if (old.version() != version) conflict();
        return old;
    }

    private void conflict() {
        throw new BusinessException(412, "VERSION_CONFLICT", "数据已被修改，请刷新后重试");
    }

    private void audit(UUID id, UUID actor, String action) {
        writes.audit(UUID.randomUUID(), actor, action, "storage_config", id);
        writes.event(UUID.randomUUID(), id, "storage_config." + action, "{\"id\":\"" + id + "\"}");
    }

    private StorageDtos.View view(StorageEntity r) {
        return new StorageDtos.View(
                r.id(),
                r.version(),
                r.createdAt(),
                r.updatedAt(),
                r.name(),
                r.provider(),
                r.bucket(),
                r.region(),
                r.endpoint(),
                r.credentialRef(),
                r.access(),
                r.status());
    }

    private StorageDtos.Write normalize(StorageDtos.Write w) {
        try {
            var uri = java.net.URI.create(w.endpoint());
            String host = uri.getHost();
            boolean local =
                    java.util.Set.of("localhost", "127.0.0.1", "minio")
                            .contains(host == null ? "" : host);
            if (uri.getPort() == 0
                    || uri.getPort() > 65535
                    || host == null
                    || uri.getUserInfo() != null
                    || uri.getRawQuery() != null
                    || uri.getRawFragment() != null
                    || !(uri.getPath().isEmpty() || uri.getPath().equals("/"))
                    || !("https".equalsIgnoreCase(uri.getScheme())
                            || (w.provider() == StorageDtos.Provider.S3_COMPATIBLE
                                    && local
                                    && "http".equalsIgnoreCase(uri.getScheme()))))
                throw new IllegalArgumentException();
            String normalized =
                    new java.net.URI(
                                    uri.getScheme().toLowerCase(java.util.Locale.ROOT),
                                    null,
                                    host.toLowerCase(java.util.Locale.ROOT),
                                    uri.getPort(),
                                    null,
                                    null,
                                    null)
                            .toString();
            return new StorageDtos.Write(
                    w.name(),
                    w.provider(),
                    w.bucket(),
                    w.region(),
                    normalized,
                    w.credentialRef(),
                    w.access(),
                    w.status());
        } catch (IllegalArgumentException | java.net.URISyntaxException e) {
            throw new BusinessException(
                    422, "STORAGE_ENDPOINT_INVALID", "存储地址须为 HTTPS 根地址；仅本地 MinIO 可使用 HTTP");
        }
    }
}
