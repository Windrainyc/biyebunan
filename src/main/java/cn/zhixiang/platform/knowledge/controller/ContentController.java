package cn.zhixiang.platform.knowledge.controller;

import cn.zhixiang.platform.identity.model.Actor;
import cn.zhixiang.platform.knowledge.dto.ContentDtos;
import cn.zhixiang.platform.knowledge.service.ContentService;
import cn.zhixiang.platform.shared.dto.PageResult;
import cn.zhixiang.platform.shared.dto.Versions;
import cn.zhixiang.platform.shared.service.IdempotentWrites;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/** 知识库独立平台入口，学生范围由数据库执行。 */
@RestController
@RequestMapping("/api/v1")
public class ContentController {
    private final ContentService service;
    private final IdempotentWrites writes;

    /**
     * 创建接口。
     *
     * @param service 内容用例
     * @param writes 幂等写
     */
    public ContentController(ContentService service, IdempotentWrites writes) {
        this.service = service;
        this.writes = writes;
    }

    /**
     * 授权分页查询。
     *
     * @param page 页码
     * @param size 页大小
     * @param q 搜索词
     * @param status 状态
     * @param sort 排序
     * @param categoryId 分类
     * @param tagId 标签
     * @param contentType 类型
     * @param actor 身份
     * @return 分页
     */
    @GetMapping("/admin/knowledge")
    @PreAuthorize("hasAuthority('get_admin_knowledge')")
    public PageResult<ContentDtos.View> adminList(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) UUID tagId,
            @RequestParam(required = false) String contentType,
            @AuthenticationPrincipal Actor actor) {
        return service.list(
                new ContentDtos.Query(page, size, q, status, sort, categoryId, tagId, contentType),
                actor);
    }

    /**
     * 授权详情。
     *
     * @param id 内容
     * @param actor 身份
     * @return 资源及版本
     */
    @GetMapping("/admin/knowledge/{id}")
    @PreAuthorize("hasAuthority('get_admin_knowledge_id')")
    public ResponseEntity<ContentDtos.View> adminGet(
            @PathVariable UUID id, @AuthenticationPrincipal Actor actor) {
        var r = service.get(id, actor);
        return ResponseEntity.ok().eTag(Versions.etag(r.version())).body(r);
    }

    /**
     * 授权分页查询。
     *
     * @param page 页码
     * @param size 页大小
     * @param q 搜索词
     * @param status 状态
     * @param sort 排序
     * @param categoryId 分类
     * @param tagId 标签
     * @param contentType 类型
     * @param actor 身份
     * @return 分页
     */
    @GetMapping("/student/knowledge")
    @PreAuthorize("hasAuthority('get_student_knowledge')")
    public PageResult<ContentDtos.View> studentList(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) UUID tagId,
            @RequestParam(required = false) String contentType,
            @AuthenticationPrincipal Actor actor) {
        return service.list(
                new ContentDtos.Query(page, size, q, status, sort, categoryId, tagId, contentType),
                actor);
    }

    /**
     * 授权详情。
     *
     * @param id 内容
     * @param actor 身份
     * @return 资源及版本
     */
    @GetMapping("/student/knowledge/{id}")
    @PreAuthorize("hasAuthority('get_student_knowledge_id')")
    public ResponseEntity<ContentDtos.View> studentGet(
            @PathVariable UUID id, @AuthenticationPrincipal Actor actor) {
        var r = service.get(id, actor);
        return ResponseEntity.ok().eTag(Versions.etag(r.version())).body(r);
    }

    /**
     * 新建草稿。
     *
     * @param w 输入
     * @param key 幂等键
     * @param actor 管理员
     * @return 201
     */
    @PostMapping("/admin/knowledge")
    @PreAuthorize("hasAuthority('post_admin_knowledge')")
    public ResponseEntity<String> create(
            @Valid @RequestBody ContentDtos.Write w,
            @RequestHeader("Idempotency-Key") String key,
            @AuthenticationPrincipal Actor actor) {
        return writes.execute(
                actor.id(),
                "POST",
                "/api/v1/admin/knowledge",
                key,
                w,
                () -> {
                    var r = service.create(w, actor);
                    return ResponseEntity.status(201).eTag(Versions.etag(r.version())).body(r);
                });
    }

    /**
     * 编辑草稿。
     *
     * @param id 内容
     * @param w 输入
     * @param match 版本
     * @param key 幂等键
     * @param actor 管理员
     * @return 资源
     */
    @PutMapping("/admin/knowledge/{id}")
    @PreAuthorize("hasAuthority('put_admin_knowledge_id')")
    public ResponseEntity<String> update(
            @PathVariable UUID id,
            @Valid @RequestBody ContentDtos.Write w,
            @RequestHeader("If-Match") String match,
            @RequestHeader("Idempotency-Key") String key,
            @AuthenticationPrincipal Actor actor) {
        long v = Versions.parse(match);
        return writes.execute(
                actor.id(),
                "PUT",
                "/api/v1/admin/knowledge/" + id,
                key,
                List.of(v, w),
                () -> {
                    var r = service.update(id, v, w, actor);
                    return ResponseEntity.ok().eTag(Versions.etag(r.version())).body(r);
                });
    }

    /**
     * 软删除非发布内容。
     *
     * @param id 内容
     * @param match 版本
     * @param key 幂等键
     * @param actor 管理员
     * @return 204
     */
    @DeleteMapping("/admin/knowledge/{id}")
    @PreAuthorize("hasAuthority('delete_admin_knowledge_id')")
    public ResponseEntity<String> delete(
            @PathVariable UUID id,
            @RequestHeader("If-Match") String match,
            @RequestHeader("Idempotency-Key") String key,
            @AuthenticationPrincipal Actor actor) {
        long v = Versions.parse(match);
        return writes.execute(
                actor.id(),
                "DELETE",
                "/api/v1/admin/knowledge/" + id,
                key,
                v,
                () -> {
                    service.delete(id, v, actor);
                    return ResponseEntity.noContent().build();
                });
    }

    /**
     * 受控状态变更。
     *
     * @param id 内容
     * @param w 原因
     * @param match 版本
     * @param key 幂等键
     * @param actor 身份
     * @return 内容
     */
    @PostMapping("/admin/knowledge/{id}/submit-review")
    @PreAuthorize("hasAuthority('post_admin_knowledge_id_submit_review')")
    public ResponseEntity<String> review(
            @PathVariable UUID id,
            @Valid @RequestBody ContentDtos.Reason w,
            @RequestHeader("If-Match") String match,
            @RequestHeader("Idempotency-Key") String key,
            @AuthenticationPrincipal Actor actor) {
        long v = Versions.parse(match);
        return writes.execute(
                actor.id(),
                "POST",
                "/api/v1/admin/knowledge/" + id + "/submit-review",
                key,
                List.of(v, w),
                () -> {
                    var r = service.transition(id, v, "REVIEW", w.reason(), actor);
                    return ResponseEntity.ok().eTag(Versions.etag(r.version())).body(r);
                });
    }

    /**
     * 受控状态变更。
     *
     * @param id 内容
     * @param w 原因
     * @param match 版本
     * @param key 幂等键
     * @param actor 身份
     * @return 内容
     */
    @PostMapping("/admin/knowledge/{id}/publish")
    @PreAuthorize("hasAuthority('post_admin_knowledge_id_publish')")
    public ResponseEntity<String> publish(
            @PathVariable UUID id,
            @Valid @RequestBody ContentDtos.Reason w,
            @RequestHeader("If-Match") String match,
            @RequestHeader("Idempotency-Key") String key,
            @AuthenticationPrincipal Actor actor) {
        long v = Versions.parse(match);
        return writes.execute(
                actor.id(),
                "POST",
                "/api/v1/admin/knowledge/" + id + "/publish",
                key,
                List.of(v, w),
                () -> {
                    var r = service.transition(id, v, "PUBLISH", w.reason(), actor);
                    return ResponseEntity.ok().eTag(Versions.etag(r.version())).body(r);
                });
    }

    /**
     * 受控状态变更。
     *
     * @param id 内容
     * @param w 原因
     * @param match 版本
     * @param key 幂等键
     * @param actor 身份
     * @return 内容
     */
    @PostMapping("/admin/knowledge/{id}/withdraw")
    @PreAuthorize("hasAuthority('post_admin_knowledge_id_withdraw')")
    public ResponseEntity<String> withdraw(
            @PathVariable UUID id,
            @Valid @RequestBody ContentDtos.Reason w,
            @RequestHeader("If-Match") String match,
            @RequestHeader("Idempotency-Key") String key,
            @AuthenticationPrincipal Actor actor) {
        long v = Versions.parse(match);
        return writes.execute(
                actor.id(),
                "POST",
                "/api/v1/admin/knowledge/" + id + "/withdraw",
                key,
                List.of(v, w),
                () -> {
                    var r = service.transition(id, v, "WITHDRAW", w.reason(), actor);
                    return ResponseEntity.ok().eTag(Versions.etag(r.version())).body(r);
                });
    }
}
