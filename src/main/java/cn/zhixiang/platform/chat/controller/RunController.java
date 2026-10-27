package cn.zhixiang.platform.chat.controller;

import cn.zhixiang.platform.chat.dto.RunCancelRequest;
import cn.zhixiang.platform.chat.dto.RunDtos;
import cn.zhixiang.platform.chat.service.RunControlService;
import cn.zhixiang.platform.chat.service.RunService;
import cn.zhixiang.platform.identity.model.Actor;
import cn.zhixiang.platform.shared.dto.Versions;
import cn.zhixiang.platform.shared.service.IdempotentWrites;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** 学生运行创建与状态入口；客户端不能选择计费账户或供应商密钥。 */
@RestController
@RequestMapping("/api/v1/student/runs")
public class RunController {
    private final RunControlService control;
    private final RunService service;
    private final IdempotentWrites writes;

    /**
     * 创建控制器。
     *
     * @param control 取消用例
     * @param service 运行用例
     * @param writes HTTP幂等
     */
    public RunController(RunControlService control, RunService service, IdempotentWrites writes) {
        this.control = control;
        this.service = service;
        this.writes = writes;
    }

    /**
     * 创建并冻结额度。
     *
     * @param w 输入
     * @param key 幂等键
     * @param actor 学生
     * @return 202及运行状态
     */
    @PostMapping
    @PreAuthorize("hasAuthority('post_student_runs')")
    public ResponseEntity<String> create(
            @Valid @RequestBody RunDtos.Create w,
            @RequestHeader("Idempotency-Key") String key,
            @AuthenticationPrincipal Actor actor) {
        return writes.execute(
                actor.id(),
                "POST",
                "/api/v1/student/runs",
                key,
                w,
                () -> {
                    var result = service.create(w, actor);
                    return ResponseEntity.accepted()
                            .eTag(Versions.etag(result.version()))
                            .body(result);
                });
    }

    /**
     * 本人运行状态。
     *
     * @param id 运行
     * @param actor 身份
     * @return 状态及ETag
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('get_student_runs_id')")
    public ResponseEntity<RunDtos.View> get(
            @PathVariable UUID id, @AuthenticationPrincipal Actor actor) {
        var result = service.get(id, actor);
        return ResponseEntity.ok().eTag(Versions.etag(result.version())).body(result);
    }

    /**
     * 取消运行意图，幂等且单调；不要求高频变化的流ETag。
     *
     * @param id 运行
     * @param reason 原因
     * @param key 幂等键
     * @param actor 当前身份
     * @return 202及实际账务状态
     */
    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('post_student_runs_id_cancel')")
    public ResponseEntity<String> cancel(
            @PathVariable UUID id,
            @Valid @RequestBody RunCancelRequest reason,
            @RequestHeader("Idempotency-Key") String key,
            @AuthenticationPrincipal Actor actor) {
        return writes.execute(
                actor.id(),
                "POST",
                "/api/v1/student/runs/" + id + "/cancel",
                key,
                reason,
                () -> {
                    var result = control.cancel(id, reason.reason(), actor);
                    return ResponseEntity.accepted()
                            .eTag(Versions.etag(result.version()))
                            .body(result);
                });
    }
}
