package cn.zhixiang.platform.configuration.controller;

import cn.zhixiang.platform.configuration.dto.OssDtos;
import cn.zhixiang.platform.configuration.service.OssService;
import cn.zhixiang.platform.identity.model.Actor;
import cn.zhixiang.platform.shared.dto.Versions;
import cn.zhixiang.platform.shared.service.IdempotentWrites;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/** 管理员OSS专用配置入口；存储权限、幂等与CAS由服务端控制。 */
@RestController
@RequestMapping("/api/v1/admin/oss-service")
public class OssController {
    private final cn.zhixiang.platform.configuration.service.StorageMigrationService migration;
    private final OssService service;
    private final IdempotentWrites writes;

    /**
     * 按存储配置的权限、版本与生命周期约束执行操作。
     *
     * @param service 配置用例
     * @param writes 幂等协调
     * @param migration 迁移服务
     */
    public OssController(
            OssService service,
            IdempotentWrites writes,
            cn.zhixiang.platform.configuration.service.StorageMigrationService migration) {
        this.migration = migration;
        this.service = service;
        this.writes = writes;
    }

    /**
     * 按存储配置的权限、版本与生命周期约束执行操作。
     *
     * @return 脱敏配置
     */
    @GetMapping
    @PreAuthorize("hasAuthority('storages:read')")
    public List<OssDtos.View> list() {
        return service.list();
    }

    /**
     * 按存储配置的权限、版本与生命周期约束执行操作。
     *
     * @param body 配置
     * @param key 幂等键
     * @param actor 身份
     * @return 脱敏保存结果
     */
    @PostMapping
    @PreAuthorize("hasAuthority('storages:write')")
    public ResponseEntity<String> save(
            @Valid @RequestBody OssDtos.Save body,
            @RequestHeader("Idempotency-Key") String key,
            @AuthenticationPrincipal Actor actor) {
        return writes.execute(
                actor.id(),
                "POST",
                "/api/v1/admin/oss-service",
                key,
                body,
                () -> ResponseEntity.ok(service.save(body, actor.id())));
    }

    /**
     * 按存储配置的权限、版本与生命周期约束执行操作。
     *
     * @param id 配置
     * @param match 密钥版本
     * @param actor 身份
     * @return 只读安全检查
     */
    @PostMapping("/{id}/check")
    @PreAuthorize("hasAuthority('storages:write')")
    public OssDtos.View check(
            @PathVariable UUID id,
            @RequestHeader("If-Match") String match,
            @AuthenticationPrincipal Actor actor) {
        return service.check(id, Versions.parse(match), actor.id());
    }

    /**
     * 按存储配置的权限、版本与生命周期约束执行操作。
     *
     * @param id 配置
     * @param match 密钥版本
     * @param key 幂等键
     * @param actor 身份
     * @return 默认上传配置
     */
    @PostMapping("/{id}/activate")
    @PreAuthorize("hasAuthority('storages:write')")
    public ResponseEntity<String> activate(
            @PathVariable UUID id,
            @RequestHeader("If-Match") String match,
            @RequestHeader("Idempotency-Key") String key,
            @AuthenticationPrincipal Actor actor) {
        long v = Versions.parse(match);
        return writes.execute(
                actor.id(),
                "POST",
                "/api/v1/admin/oss-service/" + id + "/activate",
                key,
                List.of(id, v),
                () -> ResponseEntity.ok(service.activate(id, v, actor.id())));
    }

    /**
     * 按存储配置的权限、版本与生命周期约束执行操作。
     *
     * @return 迁移清单真实计数
     */
    @GetMapping("/migration")
    @PreAuthorize("hasAuthority('storages:read')")
    public cn.zhixiang.platform.configuration.dto.StorageMigrationView migration() {
        return migration.status();
    }

    /**
     * 按存储配置的权限、版本与生命周期约束执行操作。
     *
     * @param id 目标配置
     * @param match 密钥版本
     * @param key 幂等键
     * @param actor 管理员
     * @return 排队进度
     */
    @PostMapping("/{id}/migrate")
    @PreAuthorize("hasAuthority('storages:write')")
    public ResponseEntity<String> migrate(
            @PathVariable UUID id,
            @RequestHeader("If-Match") String match,
            @RequestHeader("Idempotency-Key") String key,
            @AuthenticationPrincipal Actor actor) {
        long v = Versions.parse(match);
        return writes.execute(
                actor.id(),
                "POST",
                "/api/v1/admin/oss-service/" + id + "/migrate",
                key,
                List.of(id, v),
                () -> ResponseEntity.accepted().body(migration.start(id, v, actor.id())));
    }
}
