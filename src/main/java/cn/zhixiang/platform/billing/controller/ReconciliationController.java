package cn.zhixiang.platform.billing.controller;

import cn.zhixiang.platform.billing.dto.ReconciliationReasonRequest;
import cn.zhixiang.platform.billing.service.ReconciliationService;
import cn.zhixiang.platform.identity.model.Actor;
import cn.zhixiang.platform.shared.dto.Versions;
import cn.zhixiang.platform.shared.service.IdempotentWrites;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** 核对入口，不接收客户端声称的供应商用量或无费用结论。 */
@RestController
public class ReconciliationController {
    private final ReconciliationService service;
    private final IdempotentWrites writes;

    /**
     * 创建入口。
     *
     * @param service 核对用例
     * @param writes 幂等事务
     */
    public ReconciliationController(ReconciliationService service, IdempotentWrites writes) {
        this.service = service;
        this.writes = writes;
    }

    /**
     * 发起异步核对。
     *
     * @param id 运行
     * @param body 原因
     * @param key 幂等键
     * @param actor 管理员
     * @return 202任务与ETag
     */
    @PostMapping("/api/v1/admin/runs/{id}/reconcile")
    @PreAuthorize("hasAuthority('billing:reconcile')")
    public ResponseEntity<String> create(
            @PathVariable UUID id,
            @Valid @RequestBody ReconciliationReasonRequest body,
            @RequestHeader("Idempotency-Key") String key,
            @AuthenticationPrincipal Actor actor) {
        return writes.execute(
                actor.id(),
                "POST",
                "/api/v1/admin/runs/" + id + "/reconcile",
                key,
                body,
                () -> {
                    var result = service.create(id, body.reason(), actor);
                    return ResponseEntity.accepted()
                            .eTag(Versions.etag(result.version()))
                            .body(result);
                });
    }
}
