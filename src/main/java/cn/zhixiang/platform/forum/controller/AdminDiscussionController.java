package cn.zhixiang.platform.forum.controller;

import cn.zhixiang.platform.forum.dto.CommentDtos;
import cn.zhixiang.platform.forum.dto.PostDtos;
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

/** admin平台评论与举报入口，身份来自服务端认证。 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminDiscussionController {
    private final DiscussionService service;
    private final IdempotentWrites writes;

    /**
     * 构造入口。
     *
     * @param service 业务用例
     * @param writes 幂等事务
     */
    public AdminDiscussionController(DiscussionService service, IdempotentWrites writes) {
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
    @PreAuthorize("hasAuthority('get_admin_posts_id_comments')")
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
     * post_admin_comments_id_moderation用例。
     *
     * @param id 目标ID
     * @param w 已验证请求
     * @param key 幂等键
     * @param actor 服务端身份
     * @param match CAS版本
     * @return 持久化结果
     */
    @PostMapping("/comments/{id}/moderation")
    @PreAuthorize("hasAuthority('post_admin_comments_id_moderation')")
    public ResponseEntity<String> moderate(
            @PathVariable UUID id,
            @Valid @RequestBody PostDtos.Moderation w,
            @RequestHeader("Idempotency-Key") String key,
            @AuthenticationPrincipal Actor actor,
            @RequestHeader("If-Match") String match) {
        long v = Versions.parse(match);
        return writes.execute(
                actor.id(),
                "POST",
                "/api/v1/admin/comments/" + id + "/moderation",
                key,
                List.of(v, w),
                () -> {
                    var result = service.moderate(id, v, w, actor);
                    return ResponseEntity.status(200)
                            .eTag(Versions.etag(result.version()))
                            .body(result);
                });
    }

    /**
     * post_admin_reports_id_decision用例。
     *
     * @param id 目标ID
     * @param w 已验证请求
     * @param key 幂等键
     * @param actor 服务端身份
     * @param match CAS版本
     * @return 持久化结果
     */
    @PostMapping("/reports/{id}/decision")
    @PreAuthorize("hasAuthority('post_admin_reports_id_decision')")
    public ResponseEntity<String> decide(
            @PathVariable UUID id,
            @Valid @RequestBody ReportDtos.Decision w,
            @RequestHeader("Idempotency-Key") String key,
            @AuthenticationPrincipal Actor actor,
            @RequestHeader("If-Match") String match) {
        long v = Versions.parse(match);
        return writes.execute(
                actor.id(),
                "POST",
                "/api/v1/admin/reports/" + id + "/decision",
                key,
                List.of(v, w),
                () -> {
                    var result = service.decide(id, v, w, actor);
                    return ResponseEntity.status(200)
                            .eTag(Versions.etag(result.version()))
                            .body(result);
                });
    }

    /**
     * 管理员举报目录。
     *
     * @param page 页码
     * @param size 页大小
     * @param q 原始理由搜索
     * @param status 处理状态
     * @param sort 排序
     * @param actor 管理员
     * @return 分页
     */
    @GetMapping("/reports")
    @PreAuthorize("hasAuthority('get_admin_reports')")
    public PageResult<ReportDtos.View> reports(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String sort,
            @AuthenticationPrincipal Actor actor) {
        return service.reports(new PageQuery(page, size, q, null, sort), status, actor);
    }
}
