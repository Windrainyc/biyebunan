package cn.zhixiang.platform.chat.controller;

import cn.zhixiang.platform.chat.service.RunStreamService;
import cn.zhixiang.platform.identity.model.Actor;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

/** 学生流订阅入口；断线恢复与模型执行彻底分离。 */
@RestController
public class RunStreamController {
    private final RunStreamService service;

    /**
     * 创建控制器。
     *
     * @param service 流用例
     */
    public RunStreamController(RunStreamService service) {
        this.service = service;
    }

    /**
     * 获取持久化事件并订阅后续增量。
     *
     * @param id 运行
     * @param cursor Last-Event-ID
     * @param actor 服务端身份
     * @return 禁止缓存/代理缓冲的SSE
     */
    @GetMapping(value = "/api/v1/student/runs/{id}/events", produces = "text/event-stream")
    @PreAuthorize("hasAuthority('get_student_runs_id_events')")
    public ResponseEntity<SseEmitter> events(
            @PathVariable UUID id,
            @RequestHeader(value = "Last-Event-ID", required = false) String cursor,
            @AuthenticationPrincipal Actor actor) {
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .header("X-Accel-Buffering", "no")
                .body(service.open(id, cursor, actor));
    }
}
