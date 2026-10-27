package cn.zhixiang.platform.billing.controller;

import cn.zhixiang.platform.billing.dto.AdjudicationDtos;
import cn.zhixiang.platform.billing.service.AdjudicationService;
import cn.zhixiang.platform.identity.model.Actor;
import cn.zhixiang.platform.shared.dto.*;
import cn.zhixiang.platform.shared.service.IdempotentWrites;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/** 人工裁决管理入口，所有写均具备幂等和独立业务权限。 */
@RestController
public class AdjudicationController {
    private final AdjudicationService service;
    private final IdempotentWrites writes;

    /**
     * 创建接口。
     *
     * @param service 裁决用例
     * @param writes 幂等协调
     */
    public AdjudicationController(AdjudicationService service, IdempotentWrites writes) {
        this.service = service;
        this.writes = writes;
    }

    /**
     * 提交证据声明，不改变账务。
     *
     * @param id 运行
     * @param body 声明
     * @param key 幂等键
     * @param actor 管理员
     * @return 201及新单据
     */
    @PostMapping("/api/v1/admin/runs/{id}/adjudications")
    @PreAuthorize("hasAuthority('billing:adjudicate')")
    public ResponseEntity<String> create(
            @PathVariable UUID id,
            @Valid @RequestBody AdjudicationDtos.Create body,
            @RequestHeader("Idempotency-Key") String key,
            @AuthenticationPrincipal Actor actor) {
        return writes.execute(
                actor.id(),
                "POST",
                "/api/v1/admin/runs/" + id + "/adjudications",
                key,
                body,
                () -> {
                    var v = service.create(id, body, actor);
                    return ResponseEntity.status(201).eTag(Versions.etag(v.version())).body(v);
                });
    }

    /**
     * 查询声明详情。
     *
     * @param id 单据
     * @param actor 管理员
     * @return 带版本的只读详情
     */
    @GetMapping("/api/v1/admin/adjudications/{id}")
    @PreAuthorize("hasAuthority('billing:adjudicate')")
    public ResponseEntity<AdjudicationDtos.View> get(
            @PathVariable UUID id, @AuthenticationPrincipal Actor actor) {
        var v = service.get(id, actor);
        return ResponseEntity.ok().eTag(Versions.etag(v.version())).body(v);
    }

    /**
     * 分页查询待审和历史声明。
     *
     * @param runId 可空运行过滤
     * @param state 可空状态过滤
     * @param page 页码
     * @param size 页大小
     * @param actor 管理员
     * @return 分页结果
     */
    @GetMapping("/api/v1/admin/adjudications")
    @PreAuthorize("hasAuthority('billing:adjudicate')")
    public PageResult<AdjudicationDtos.View> list(
            @RequestParam(required = false) UUID runId,
            @RequestParam(required = false) String state,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal Actor actor) {
        return service.list(runId, state, page, size, actor);
    }

    /**
     * 双人复核，批准与账本同事务提交。
     *
     * @param id 单据
     * @param body 审核决定
     * @param key 幂等键
     * @param version 强If-Match
     * @param actor 复核管理员
     * @return 新状态及版本
     */
    @PostMapping("/api/v1/admin/adjudications/{id}/decision")
    @PreAuthorize("hasAuthority('billing:adjudicate')")
    public ResponseEntity<String> decide(
            @PathVariable UUID id,
            @Valid @RequestBody AdjudicationDtos.Decision body,
            @RequestHeader("Idempotency-Key") String key,
            @RequestHeader("If-Match") String version,
            @AuthenticationPrincipal Actor actor) {
        return writes.execute(
                actor.id(),
                "POST",
                "/api/v1/admin/adjudications/" + id + "/decision",
                key,
                Map.of("body", body, "version", version),
                () -> {
                    var v = service.decide(id, Versions.parse(version), body, actor);
                    return ResponseEntity.ok().eTag(Versions.etag(v.version())).body(v);
                });
    }
}
