package cn.zhixiang.platform.forum.controller;

import cn.zhixiang.platform.forum.dto.PostDtos;
import cn.zhixiang.platform.forum.service.PostService;
import cn.zhixiang.platform.identity.model.Actor;
import cn.zhixiang.platform.shared.dto.PageQuery;
import cn.zhixiang.platform.shared.dto.PageResult;
import cn.zhixiang.platform.shared.dto.Versions;
import cn.zhixiang.platform.shared.service.IdempotentWrites;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/** student平台帖子入口，服务端身份控制可见范围。 */
@RestController
@RequestMapping("/api/v1/student/posts")
public class StudentPostController {
    private final PostService service;
    private final IdempotentWrites writes;

    /**
     * 构造入口。
     *
     * @param service 帖子用例
     * @param writes 幂等协调
     */
    public StudentPostController(PostService service, IdempotentWrites writes) {
        this.service = service;
        this.writes = writes;
    }

    /**
     * 查询当前权限内的帖子。
     *
     * @param page 页码
     * @param size 页大小
     * @param q 标题正文搜索
     * @param status 帖子审核状态
     * @param sort 白名单时间排序
     * @param boardId 板块筛选
     * @param actor 身份
     * @return 分页
     */
    @GetMapping
    @PreAuthorize("hasAuthority('get_student_posts')")
    public PageResult<PostDtos.View> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) UUID boardId,
            @AuthenticationPrincipal Actor actor) {
        return service.list(new PageQuery(page, size, q, null, sort), status, boardId, actor);
    }

    /**
     * 读取可见详情及CAS版本。
     *
     * @param id 帖子
     * @param actor 身份
     * @return 详情
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('get_student_posts_id')")
    public ResponseEntity<PostDtos.View> get(
            @PathVariable UUID id, @AuthenticationPrincipal Actor actor) {
        var v = service.get(id, actor);
        return ResponseEntity.ok().eTag(Versions.etag(v.version())).body(v);
    }

    /**
     * 创建帖子。
     *
     * @param w 内容
     * @param key 幂等键
     * @param actor 作者
     * @return 201与帖子
     */
    @PostMapping
    @PreAuthorize("hasAuthority('post_student_posts')")
    public ResponseEntity<String> create(
            @Valid @RequestBody PostDtos.Write w,
            @RequestHeader("Idempotency-Key") String key,
            @AuthenticationPrincipal Actor actor) {
        return writes.execute(
                actor.id(),
                "POST",
                "/api/v1/student/posts",
                key,
                w,
                () -> {
                    var v = service.create(w, actor);
                    return ResponseEntity.status(201).eTag(Versions.etag(v.version())).body(v);
                });
    }

    /**
     * 编辑本人帖子。
     *
     * @param id 帖子
     * @param w 内容
     * @param match CAS
     * @param key 幂等键
     * @param actor 作者
     * @return 新版本
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('put_student_posts_id')")
    public ResponseEntity<String> update(
            @PathVariable UUID id,
            @Valid @RequestBody PostDtos.Write w,
            @RequestHeader("If-Match") String match,
            @RequestHeader("Idempotency-Key") String key,
            @AuthenticationPrincipal Actor actor) {
        long version = Versions.parse(match);
        return writes.execute(
                actor.id(),
                "PUT",
                "/api/v1/student/posts/" + id,
                key,
                List.of(version, w),
                () -> {
                    var v = service.update(id, version, w, actor);
                    return ResponseEntity.ok().eTag(Versions.etag(v.version())).body(v);
                });
    }

    /**
     * 软删除本人帖子。
     *
     * @param id 帖子
     * @param match CAS
     * @param key 幂等键
     * @param actor 作者
     * @return 204
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('delete_student_posts_id')")
    public ResponseEntity<String> delete(
            @PathVariable UUID id,
            @RequestHeader("If-Match") String match,
            @RequestHeader("Idempotency-Key") String key,
            @AuthenticationPrincipal Actor actor) {
        long version = Versions.parse(match);
        return writes.execute(
                actor.id(),
                "DELETE",
                "/api/v1/student/posts/" + id,
                key,
                version,
                () -> {
                    service.delete(id, version, actor);
                    return ResponseEntity.noContent().build();
                });
    }
}
