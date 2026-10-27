package cn.zhixiang.platform.forum.controller;

import cn.zhixiang.platform.forum.dto.CommentDtos;
import cn.zhixiang.platform.forum.dto.ReportDtos;
import cn.zhixiang.platform.forum.service.DiscussionService;
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

/** student平台评论与举报入口，身份来自服务端认证。 */
@RestController
@RequestMapping("/api/v1/student")
public class StudentDiscussionController {
    private final DiscussionService service;
    private final IdempotentWrites writes;

    /**
     * 构造入口。
     *
     * @param service 业务用例
     * @param writes 幂等事务
     */
    public StudentDiscussionController(DiscussionService service, IdempotentWrites writes) {
        this.service = service;
        this.writes = writes;
    }

    /**
     * 读取可见帖子评论。
     *
     * @param id 帖子
     * @param page 页码
     * @param size 页大小
     * @param q 正文搜索
     * @param status 评论状态
     * @param sort 白名单排序
     * @param actor 当前身份
     * @return 分页
     */
    @GetMapping("/posts/{id}/comments")
    @PreAuthorize("hasAuthority('get_student_posts_id_comments')")
    public PageResult<CommentDtos.View> comments(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String sort,
            @AuthenticationPrincipal Actor actor) {
        return service.comments(id, new PageQuery(page, size, q, null, sort), status, actor);
    }

    /**
     * post_student_posts_id_comments用例。
     *
     * @param id 目标ID
     * @param w 已验证请求
     * @param key 幂等键
     * @param actor 服务端身份
     * @return 持久化结果
     */
    @PostMapping("/posts/{id}/comments")
    @PreAuthorize("hasAuthority('post_student_posts_id_comments')")
    public ResponseEntity<String> create(
            @PathVariable UUID id,
            @Valid @RequestBody CommentDtos.Write w,
            @RequestHeader("Idempotency-Key") String key,
            @AuthenticationPrincipal Actor actor) {
        return writes.execute(
                actor.id(),
                "POST",
                "/api/v1/student/posts/" + id + "/comments",
                key,
                w,
                () -> {
                    var result = service.create(id, w, actor);
                    return ResponseEntity.status(201)
                            .eTag(Versions.etag(result.version()))
                            .body(result);
                });
    }

    /**
     * post_student_posts_id_reports用例。
     *
     * @param id 目标ID
     * @param w 已验证请求
     * @param key 幂等键
     * @param actor 服务端身份
     * @return 持久化结果
     */
    @PostMapping("/posts/{id}/reports")
    @PreAuthorize("hasAuthority('post_student_posts_id_reports')")
    public ResponseEntity<String> report(
            @PathVariable UUID id,
            @Valid @RequestBody ReportDtos.Reason w,
            @RequestHeader("Idempotency-Key") String key,
            @AuthenticationPrincipal Actor actor) {
        return writes.execute(
                actor.id(),
                "POST",
                "/api/v1/student/posts/" + id + "/reports",
                key,
                w,
                () -> {
                    var result = service.report(id, w, actor);
                    return ResponseEntity.status(201)
                            .eTag(Versions.etag(result.version()))
                            .body(result);
                });
    }

    /**
     * 删除本人评论。
     *
     * @param id 评论
     * @param match CAS
     * @param key 幂等键
     * @param actor 作者
     * @return 204
     */
    @DeleteMapping("/comments/{id}")
    @PreAuthorize("hasAuthority('delete_student_comments_id')")
    public ResponseEntity<String> delete(
            @PathVariable UUID id,
            @RequestHeader("If-Match") String match,
            @RequestHeader("Idempotency-Key") String key,
            @AuthenticationPrincipal Actor actor) {
        long v = Versions.parse(match);
        return writes.execute(
                actor.id(),
                "DELETE",
                "/api/v1/student/comments/" + id,
                key,
                v,
                () -> {
                    service.delete(id, v, actor);
                    return ResponseEntity.noContent().build();
                });
    }
}
