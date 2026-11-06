package cn.zhixiang.platform.configuration.service;

import cn.zhixiang.platform.configuration.dto.*;
import cn.zhixiang.platform.configuration.mapper.*;
import cn.zhixiang.platform.shared.exception.BusinessException;
import cn.zhixiang.platform.shared.mapper.WriteMapper;
import cn.zhixiang.platform.shared.security.SecretCipher;
import cn.zhixiang.platform.storage.adapter.OssObjectStorage;
import cn.zhixiang.platform.storage.port.ObjectStorage;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

/** OSS凭证管理与只读安全检查；不自动创建桶或迁移、删除旧对象。 */
@Service
public class OssService {
    private final StorageMapper stores;
    private final StorageSecretMapper secrets;
    private final SecretCipher cipher;
    private final WriteMapper writes;
    private final OssObjectStorage oss;
    private final TransactionTemplate tx;

    /**
     * 按存储配置的权限、版本与生命周期约束执行操作。
     *
     * @param stores 元数据
     * @param secrets 密文
     * @param cipher 加密
     * @param writes 审计
     * @param oss 官方端口
     * @param tm 短事务
     */
    public OssService(
            StorageMapper stores,
            StorageSecretMapper secrets,
            SecretCipher cipher,
            WriteMapper writes,
            OssObjectStorage oss,
            PlatformTransactionManager tm) {
        this.stores = stores;
        this.secrets = secrets;
        this.cipher = cipher;
        this.writes = writes;
        this.oss = oss;
        this.tx = new TransactionTemplate(tm);
    }

    /**
     * 按存储配置的权限、版本与生命周期约束执行操作。
     *
     * @return 已配置OSS列表，不返回密钥或密文
     */
    public List<OssDtos.View> list() {
        return secrets.ids().stream().map(this::get).toList();
    }

    /**
     * 按存储配置的权限、版本与生命周期约束执行操作。
     *
     * @param id 配置
     * @return 安全状态
     */
    public OssDtos.View get(UUID id) {
        var s = stores.get(id);
        var k = secrets.get(id);
        if (s == null || k == null)
            throw new BusinessException(404, "RESOURCE_NOT_FOUND", "OSS配置不存在");
        return new OssDtos.View(
                id,
                k.version(),
                s.bucket(),
                s.region(),
                s.endpoint(),
                k.accessTail(),
                k.checkedAt(),
                k.checkError(),
                id.equals(secrets.defaultStorage()));
    }

    /**
     * 按存储配置的权限、版本与生命周期约束执行操作。
     *
     * @param w 新密钥
     * @param actor 管理员
     * @return 安全状态
     */
    @Transactional(rollbackFor = Exception.class)
    public OssDtos.View save(OssDtos.Save w, UUID actor) {
        UUID id = w.id() == null ? UUID.randomUUID() : w.id();
        String endpoint = "https://oss-" + w.region() + ".aliyuncs.com";
        OssObjectStorage.validate(
                new ObjectStorage.Target(
                        "ALIYUN_OSS", endpoint, w.region(), w.bucket(), "oss-" + id));
        if (w.id() == null) {
            if (w.version() != 0) conflict();
            stores.insert(
                    id,
                    new StorageDtos.Write(
                            "阿里云OSS · " + w.bucket(),
                            StorageDtos.Provider.ALIYUN_OSS,
                            w.bucket(),
                            w.region(),
                            endpoint,
                            "oss-" + id,
                            StorageDtos.Access.PRIVATE,
                            StorageDtos.Status.ENABLED),
                    actor);
        } else {
            var old = stores.lock(id);
            var key = secrets.get(id);
            if (old == null || key == null || key.version() != w.version()) conflict();
            if (!old.bucket().equals(w.bucket()) || !old.region().equals(w.region()))
                throw new BusinessException(409, "OSS_TARGET_IMMUTABLE", "更换桶或区域请新增配置，保留历史文件位置");
        }
        secrets.save(
                id,
                cipher.encrypt("oss-access:" + id, w.accessKeyId()),
                cipher.encrypt("oss-secret:" + id, w.accessKeySecret()),
                w.accessKeyId().substring(Math.max(0, w.accessKeyId().length() - 4)));
        writes.audit(UUID.randomUUID(), actor, "CREDENTIAL_SAVED", "storage_config", id);
        return get(id);
    }

    /**
     * 按存储配置的权限、版本与生命周期约束执行操作。
     *
     * @param id 配置
     * @param version 密钥快照
     * @param actor 管理员
     * @return 检查结果，网络IO不占用事务
     */
    public OssDtos.View check(UUID id, long version, UUID actor) {
        var state = get(id);
        if (state.version() != version) conflict();
        var s = stores.get(id);
        String error = null;
        try {
            oss.probe(
                    new ObjectStorage.Target(
                            "ALIYUN_OSS", s.endpoint(), s.region(), s.bucket(), s.credentialRef()));
        } catch (BusinessException e) {
            error = e.code();
        }
        final String code = error;
        tx.executeWithoutResult(
                x -> {
                    stores.lock(id);
                    if (secrets.checked(id, version, code) != 1) conflict();
                    writes.audit(UUID.randomUUID(), actor, "OSS_CHECKED", "storage_config", id);
                });
        return get(id);
    }

    /**
     * 按存储配置的权限、版本与生命周期约束执行操作。
     *
     * @param id 已通过检查配置
     * @param version 密钥版本
     * @param actor 操作者
     * @return 默认上传状态
     */
    @Transactional(rollbackFor = Exception.class)
    public OssDtos.View activate(UUID id, long version, UUID actor) {
        var storage = stores.lock(id);
        if (storage == null || storage.status() != StorageDtos.Status.ENABLED)
            throw new BusinessException(422, "STORAGE_DISABLED", "存储配置未启用");
        var state = get(id);
        if (state.version() != version) conflict();
        if (state.checkedAt() == null
                || state.checkError() != null
                || state.checkedAt().isBefore(java.time.OffsetDateTime.now().minusHours(24)))
            throw new BusinessException(422, "OSS_CHECK_REQUIRED", "请先完成最近24小时的安全检查");
        secrets.setDefault(id);
        writes.audit(UUID.randomUUID(), actor, "DEFAULT_UPLOAD_CHANGED", "storage_config", id);
        return get(id);
    }

    private void conflict() {
        throw new BusinessException(412, "VERSION_CONFLICT", "配置已变更，请刷新");
    }
}
