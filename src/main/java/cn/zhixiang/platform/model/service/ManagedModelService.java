package cn.zhixiang.platform.model.service;

import cn.zhixiang.platform.identity.model.Actor;
import cn.zhixiang.platform.identity.service.SecretVault;
import cn.zhixiang.platform.model.dto.*;
import cn.zhixiang.platform.model.entity.ManagedServiceEntity;
import cn.zhixiang.platform.model.mapper.*;
import cn.zhixiang.platform.shared.dto.Versions;
import cn.zhixiang.platform.shared.exception.BusinessException;
import cn.zhixiang.platform.shared.mapper.WriteMapper;
import cn.zhixiang.platform.shared.service.IdempotentWrites;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.*;

/** 供应商级配置与旧模型隔离，事务内同步托管模型、密钥和价格。 */
@Service
public class ManagedModelService {
    private final ManagedModelMapper mapper;
    private final ModelService models;
    private final ModelMapper modelMapper;
    private final KeyService keys;
    private final PriceMapper prices;
    private final SecretVault vault;
    private final WriteMapper audit;
    private final IdempotentWrites writes;
    private final ManagedModelNetwork network;

    /**
     * 官方服务配置操作，遵循版本与权限约束。
     *
     * @param mapper 托管仓储
     * @param models 模型服务
     * @param modelMapper 模型仓储
     * @param keys 密钥服务
     * @param prices 价格仓储
     * @param vault 密文仓库
     * @param audit 审计
     * @param writes 幂等协调
     * @param network 官方只读检查
     */
    public ManagedModelService(
            ManagedModelMapper mapper,
            ModelService models,
            ModelMapper modelMapper,
            KeyService keys,
            PriceMapper prices,
            SecretVault vault,
            WriteMapper audit,
            IdempotentWrites writes,
            ManagedModelNetwork network) {
        this.mapper = mapper;
        this.models = models;
        this.modelMapper = modelMapper;
        this.keys = keys;
        this.prices = prices;
        this.vault = vault;
        this.audit = audit;
        this.writes = writes;
        this.network = network;
    }

    /**
     * 官方服务配置操作，遵循版本与权限约束。
     *
     * @return 不含秘密的页面配置
     */
    @Transactional(readOnly = true)
    public ManagedModelDtos.View get() {
        var row = mapper.get();
        var selected = mapper.selected();
        return new ManagedModelDtos.View(
                row.version(),
                row.enabled(),
                row.secretRef() != null || mapper.legacySecret() != null,
                row.checkedAt(),
                row.checkError(),
                row.availableCodes().isBlank()
                        ? List.of()
                        : List.of(row.availableCodes().split(",")),
                OfficialModelCatalog.models().stream()
                        .map(
                                m ->
                                        new ManagedModelDtos.Model(
                                                m.code(),
                                                m.name(),
                                                selected.contains(m.code()),
                                                m.input(),
                                                m.output(),
                                                m.cached()))
                        .toList(),
                OfficialModelCatalog.ENDPOINT,
                OfficialModelCatalog.SOURCE,
                OfficialModelCatalog.VERSION,
                OfficialModelCatalog.CHECKED);
    }

    /**
     * 官方服务配置操作，遵循版本与权限约束。
     *
     * @param version 期望版本
     * @param w 配置
     * @param actor 操作者
     * @return 新版本
     */
    @Transactional(rollbackFor = Exception.class)
    public ManagedModelDtos.View save(long version, ManagedModelDtos.Save w, UUID actor) {
        var row = checked(version);
        var allowed =
                OfficialModelCatalog.models().stream().map(ManagedModelDtos.Model::code).toList();
        if (new HashSet<>(w.codes()).size() != w.codes().size() || !allowed.containsAll(w.codes()))
            throw new BusinessException(422, "MODEL_SELECTION_INVALID", "请选择官方模型列表中的模型");
        UUID ref = row.secretRef();
        String secret = w.secretValue();
        if (secret != null && !secret.isBlank()) {
            secret = secret.trim();
            if (secret.contains("\n") || secret.contains("\r"))
                throw new BusinessException(422, "MODEL_KEY_INVALID", "密钥格式不正确");
            ref = vault.put(secret);
        } else {
            if (ref == null) ref = mapper.legacySecret();
            secret = ref == null ? null : vault.get(ref);
        }
        if (secret == null) throw new BusinessException(422, "MODEL_KEY_REQUIRED", "请先填写 API Key");
        for (var template : OfficialModelCatalog.models()) {
            UUID id = mapper.managed(template.code());
            if (id == null && w.codes().contains(template.code())) {
                if (mapper.existing(template.code()) != null)
                    throw new BusinessException(
                            409, "MODEL_CODE_EXISTS", "已有同名模型，请先由管理员核对历史配置，系统不会覆盖历史记录");
                var model =
                        models.create(
                                new ModelDtos.Write(
                                        template.name(),
                                        template.code(),
                                        "OPENAI_COMPATIBLE",
                                        OfficialModelCatalog.ENDPOINT,
                                        65536,
                                        8192,
                                        ModelDtos.Status.DISABLED),
                                actor);
                id = model.id();
                mapper.bind(id, OfficialModelCatalog.VERSION);
                prices.insert(
                        UUID.randomUUID(),
                        new PriceDtos.Write(
                                id,
                                "CNY",
                                template.input(),
                                template.output(),
                                template.cached(),
                                OffsetDateTime.now(),
                                ModelDtos.Status.ENABLED),
                        actor);
            }
            if (id == null) continue;
            var model = modelMapper.lock(id);
            models.status(id, model.version(), ModelDtos.Status.DISABLED, actor);
            mapper.select(id, w.codes().contains(template.code()));
            UUID keyId = mapper.key(id);
            KeyDtos.View k;
            if (keyId == null) k = keys.create(new KeyDtos.Write("官方服务托管密钥", id, secret), actor);
            else {
                k = keys.get(keyId);
                k = keys.rotate(k.id(), k.version(), new KeyDtos.Rotate(secret, "更新官方服务凭证"), actor);
            }
            keys.status(k.id(), k.version(), ModelDtos.Status.ENABLED, actor);
        }
        if (mapper.save(ref, version) != 1) conflict();
        record(actor, "SAVED");
        return get();
    }

    /**
     * 网络检查不持数据库事务，不生成文字；提交结果时再次验证版本和身份。
     *
     * @param version 待检查版本
     * @param key 幂等键
     * @param actor 管理员
     * @return 检查结果
     */
    public ResponseEntity<String> check(long version, String key, Actor actor) {
        var row = mapper.get();
        if (row.version() != version || row.enabled()) {
            return writes.execute(
                    actor.id(),
                    "POST",
                    "/api/v1/admin/model-service/check",
                    key,
                    version,
                    () -> {
                        if (row.version() != version) conflict();
                        throw new BusinessException(409, "MODEL_DISABLE_REQUIRED", "请先停用服务再检查连接");
                    });
        }
        if (row.secretRef() == null)
            throw new BusinessException(422, "MODEL_SAVE_REQUIRED", "请先保存配置再检查连接");
        List<String> found;
        String error = null;
        try {
            found =
                    network.models(vault.get(row.secretRef())).stream()
                            .filter(
                                    code ->
                                            OfficialModelCatalog.models().stream()
                                                    .anyMatch(m -> m.code().equals(code)))
                            .distinct()
                            .sorted()
                            .toList();
        } catch (BusinessException e) {
            found = List.of();
            error = e.getMessage();
        }
        final String codes = String.join(",", found), failure = error;
        return writes.execute(
                actor.id(),
                "POST",
                "/api/v1/admin/model-service/check",
                key,
                version,
                () -> {
                    checked(version);
                    if (mapper.checked(codes, failure, version) != 1) conflict();
                    record(actor.id(), "CHECKED");
                    return response(get());
                });
    }

    /**
     * 官方服务配置操作，遵循版本与权限约束。
     *
     * @param version 版本
     * @param enabled 是否启用
     * @param actor 管理员
     * @return 新状态
     */
    @Transactional(rollbackFor = Exception.class)
    public ManagedModelDtos.View status(long version, boolean enabled, UUID actor) {
        var row = checked(version);
        var selected = mapper.selected();
        if (enabled
                && java.time.LocalDate.parse(OfficialModelCatalog.CHECKED)
                        .isBefore(
                                java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"))
                                        .minusDays(30)))
            throw new BusinessException(422, "MODEL_PRICE_STALE", "价格模板超过30天，请联系平台更新官方价目表");
        if (enabled
                && (row.secretRef() == null
                        || row.checkedAt() == null
                        || row.checkedAt().isBefore(OffsetDateTime.now().minusHours(24))
                        || row.checkError() != null
                        || selected.isEmpty()
                        || !List.of(row.availableCodes().split(",")).containsAll(selected)))
            throw new BusinessException(422, "MODEL_NOT_READY", "请保存配置并检查连接，确保所选模型均可用；检查结果有效期24小时");
        for (var t : OfficialModelCatalog.models()) {
            UUID id = mapper.managed(t.code());
            if (id != null) {
                var m = modelMapper.lock(id);
                if (enabled
                        && selected.contains(t.code())
                        && (!OfficialModelCatalog.ENDPOINT.equals(m.endpoint())
                                || !"OPENAI_COMPATIBLE".equals(m.provider())
                                || !mapper.ready(id, t.input(), t.output(), t.cached())))
                    throw new BusinessException(
                            422, "MODEL_CONFIGURATION_CHANGED", "托管模型、价格或密钥已被更改，请核对配置后重试");
                models.status(
                        id,
                        m.version(),
                        enabled && selected.contains(t.code())
                                ? ModelDtos.Status.ENABLED
                                : ModelDtos.Status.DISABLED,
                        actor);
            }
        }
        if (mapper.switchTo(enabled, version) != 1) conflict();
        record(actor, enabled ? "ENABLED" : "DISABLED");
        return get();
    }

    private ManagedServiceEntity checked(long version) {
        var row = mapper.lock();
        if (row.version() != version) conflict();
        return row;
    }

    private void conflict() {
        throw new BusinessException(412, "VERSION_CONFLICT", "配置已变化，请刷新后重试");
    }

    private void record(UUID actor, String action) {
        UUID id =
                UUID.nameUUIDFromBytes(
                        "deepseek".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        audit.audit(UUID.randomUUID(), actor, action, "managed_model_service", id);
        audit.event(
                UUID.randomUUID(),
                id,
                "managed_model_service." + action,
                "{\"provider\":\"deepseek\"}");
    }

    private ResponseEntity<ManagedModelDtos.View> response(ManagedModelDtos.View v) {
        return ResponseEntity.ok().eTag(Versions.etag(v.version())).body(v);
    }
}
