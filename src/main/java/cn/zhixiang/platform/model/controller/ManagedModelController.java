package cn.zhixiang.platform.model.controller;

import cn.zhixiang.platform.identity.model.Actor;
import cn.zhixiang.platform.model.dto.ManagedModelDtos;
import cn.zhixiang.platform.model.service.ManagedModelService;
import cn.zhixiang.platform.shared.dto.Versions;
import cn.zhixiang.platform.shared.service.IdempotentWrites;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 官方模型服务管理入口；复用模型、价格、密钥的组合权限。 */
@RestController
@RequestMapping("/api/v1/admin/model-service")
public class ManagedModelController {
    private final ManagedModelService service;
    private final IdempotentWrites writes;

    /**
     * 官方服务配置操作，遵循版本与权限约束。
     *
     * @param service 业务服务
     * @param writes 幂等协调
     */
    public ManagedModelController(ManagedModelService service, IdempotentWrites writes) {
        this.service = service;
        this.writes = writes;
    }

    /**
     * 官方服务配置操作，遵循版本与权限约束。
     *
     * @return 安全配置与版本
     */
    @GetMapping
    @PreAuthorize(
            "hasAuthority('models:read') and hasAuthority('model-prices:read') and"
                    + " hasAuthority('get_admin_model_keys')")
    public ResponseEntity<ManagedModelDtos.View> get() {
        return response(service.get());
    }

    /**
     * 官方服务配置操作，遵循版本与权限约束。
     *
     * @param body 配置
     * @param match 版本
     * @param key 幂等键
     * @param actor 管理员
     * @return 保存结果
     */
    @PutMapping
    @PreAuthorize(
            "hasAuthority('models:write') and hasAuthority('model-prices:write') and"
                    + " hasAuthority('post_admin_model_keys') and"
                    + " hasAuthority('post_admin_model_keys_id_rotate') and"
                    + " hasAuthority('post_admin_model_keys_id_status')")
    public ResponseEntity<String> save(
            @Valid @RequestBody ManagedModelDtos.Save body,
            @RequestHeader("If-Match") String match,
            @RequestHeader("Idempotency-Key") String key,
            @AuthenticationPrincipal Actor actor) {
        long v = Versions.parse(match);
        return writes.execute(
                actor.id(),
                "PUT",
                "/api/v1/admin/model-service",
                key,
                List.of(v, body),
                () -> response(service.save(v, body, actor.id())));
    }

    /**
     * 官方服务配置操作，遵循版本与权限约束。
     *
     * @param match 版本
     * @param key 幂等键
     * @param actor 管理员
     * @return 官方只读检查
     */
    @PostMapping("/check")
    @PreAuthorize(
            "hasAuthority('models:write') and hasAuthority('model-prices:write') and"
                    + " hasAuthority('post_admin_model_keys') and"
                    + " hasAuthority('post_admin_model_keys_id_rotate') and"
                    + " hasAuthority('post_admin_model_keys_id_status')")
    public ResponseEntity<String> check(
            @RequestHeader("If-Match") String match,
            @RequestHeader("Idempotency-Key") String key,
            @AuthenticationPrincipal Actor actor) {
        return service.check(Versions.parse(match), key, actor);
    }

    /**
     * 官方服务配置操作，遵循版本与权限约束。
     *
     * @param body 开关
     * @param match 版本
     * @param key 幂等键
     * @param actor 管理员
     * @return 新状态
     */
    @PostMapping("/status")
    @PreAuthorize(
            "hasAuthority('models:write') and hasAuthority('model-prices:write') and"
                    + " hasAuthority('post_admin_model_keys') and"
                    + " hasAuthority('post_admin_model_keys_id_rotate') and"
                    + " hasAuthority('post_admin_model_keys_id_status')")
    public ResponseEntity<String> status(
            @Valid @RequestBody ManagedModelDtos.Switch body,
            @RequestHeader("If-Match") String match,
            @RequestHeader("Idempotency-Key") String key,
            @AuthenticationPrincipal Actor actor) {
        long v = Versions.parse(match);
        return writes.execute(
                actor.id(),
                "POST",
                "/api/v1/admin/model-service/status",
                key,
                List.of(v, body),
                () -> response(service.status(v, body.enabled(), actor.id())));
    }

    private ResponseEntity<ManagedModelDtos.View> response(ManagedModelDtos.View v) {
        return ResponseEntity.ok().eTag(Versions.etag(v.version())).body(v);
    }
}
