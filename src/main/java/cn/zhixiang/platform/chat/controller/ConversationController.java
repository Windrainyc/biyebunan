package cn.zhixiang.platform.chat.controller;

import cn.zhixiang.platform.chat.dto.ConversationDtos;
import cn.zhixiang.platform.chat.service.ConversationService;
import cn.zhixiang.platform.identity.model.Actor;
import cn.zhixiang.platform.shared.dto.PageResult;
import cn.zhixiang.platform.shared.dto.Versions;
import cn.zhixiang.platform.shared.exception.BusinessException;
import cn.zhixiang.platform.shared.service.IdempotentWrites;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/** 学生独立会话入口，不提供客户端角色切换。 */
@RestController
@RequestMapping("/api/v1/student/conversations")
public class ConversationController {
    private final ConversationService service;
    private final IdempotentWrites writes;

    /**
     * 创建接口。
     *
     * @param service 用例
     * @param writes 幂等写
     */
    public ConversationController(ConversationService service, IdempotentWrites writes) {
        this.service = service;
        this.writes = writes;
    }

    /**
     * 本人会话分页。
     *
     * @param page 页码
     * @param size 页大小
     * @param q 标题搜索
     * @param sort 排序
     * @param status 无状态资源，不接受该过滤
     * @param actor 学生
     * @return 分页
     */
    @GetMapping
    @PreAuthorize("hasAuthority('get_student_conversations')")
    public PageResult<ConversationDtos.View> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String status,
            @AuthenticationPrincipal Actor actor) {
        if (status != null) throw new BusinessException(400, "INVALID_FILTER", "会话不提供状态筛选");
        return service.list(new ConversationDtos.Query(page, size, q, sort), actor);
    }

    /**
     * 获取本人会话。
     *
     * @param id 会话
     * @param actor 学生
     * @return 带ETag会话
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('get_student_conversations_id')")
    public ResponseEntity<ConversationDtos.View> get(
            @PathVariable UUID id, @AuthenticationPrincipal Actor actor) {
        var r = service.get(id, actor);
        return ResponseEntity.ok().eTag(Versions.etag(r.version())).body(r);
    }

    /**
     * 创建项目会话。
     *
     * @param w 输入
     * @param key 幂等键
     * @param actor 学生
     * @return 201
     */
    @PostMapping
    @PreAuthorize("hasAuthority('post_student_conversations')")
    public ResponseEntity<String> create(
            @Valid @RequestBody ConversationDtos.Write w,
            @RequestHeader("Idempotency-Key") String key,
            @AuthenticationPrincipal Actor actor) {
        return writes.execute(
                actor.id(),
                "POST",
                "/api/v1/student/conversations",
                key,
                w,
                () -> {
                    var r = service.create(w, actor);
                    return ResponseEntity.status(201).eTag(Versions.etag(r.version())).body(r);
                });
    }

    /**
     * 隐藏会话。
     *
     * @param id ID
     * @param match 版本
     * @param key 幂等键
     * @param actor 学生
     * @return 204
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('delete_student_conversations_id')")
    public ResponseEntity<String> delete(
            @PathVariable UUID id,
            @RequestHeader("If-Match") String match,
            @RequestHeader("Idempotency-Key") String key,
            @AuthenticationPrincipal Actor actor) {
        long v = Versions.parse(match);
        return writes.execute(
                actor.id(),
                "DELETE",
                "/api/v1/student/conversations/" + id,
                key,
                v,
                () -> {
                    service.delete(id, v, actor);
                    return ResponseEntity.noContent().build();
                });
    }
}
