package cn.zhixiang.platform.notification.controller;

import cn.zhixiang.platform.identity.model.Actor;
import cn.zhixiang.platform.notification.dto.AnnouncementDtos;
import cn.zhixiang.platform.notification.service.AnnouncementService;
import cn.zhixiang.platform.shared.dto.PageResult;
import cn.zhixiang.platform.shared.service.IdempotentWrites;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** admin端公告正文及受控发布入口。 */
@RestController
@RequestMapping("/api/v1/admin/announcements")
public class AdminAnnouncementController {
    private final AnnouncementService service;
    private final IdempotentWrites writes;

    /**
     * 创建接口。
     *
     * @param service 公告用例
     * @param writes 幂等事务
     */
    public AdminAnnouncementController(AnnouncementService service, IdempotentWrites writes) {
        this.service = service;
        this.writes = writes;
    }

    /**
     * 读取授权公告；未收到且无管理权限时404。
     *
     * @param id 公告ID
     * @param actor 当前身份
     * @return 完整正文
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('get_admin_notifications')")
    public AnnouncementDtos.View get(@PathVariable UUID id, @AuthenticationPrincipal Actor actor) {
        return service.get(id, actor);
    }

    /**
     * 查询发送记录。
     *
     * @param page 页码
     * @param size 页大小
     * @param q 标题
     * @param actor 身份
     * @return 发送记录
     */
    @GetMapping
    @PreAuthorize("hasAuthority('announcements:read')")
    public PageResult<AnnouncementDtos.View> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String q,
            @AuthenticationPrincipal Actor actor) {
        return service.list(page, size, q, actor);
    }

    /**
     * 查询脱敏接收者。
     *
     * @param page 页码
     * @param size 页大小
     * @param q 搜索
     * @param platform 平台
     * @param actor 身份
     * @return 用户分页
     */
    @GetMapping("/recipients")
    @PreAuthorize("hasAuthority('announcements:write')")
    public PageResult<AnnouncementDtos.Recipient> users(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String platform,
            @AuthenticationPrincipal Actor actor) {
        return service.users(page, size, q, platform, actor);
    }

    /**
     * 幂等发布；输入错误整笔回滚。
     *
     * @param body 发布内容
     * @param key 幂等键
     * @param actor 身份
     * @return 已发布公告
     */
    @PostMapping
    @PreAuthorize("hasAuthority('announcements:write')")
    public ResponseEntity<String> publish(
            @Valid @RequestBody AnnouncementDtos.Publish body,
            @RequestHeader("Idempotency-Key") String key,
            @AuthenticationPrincipal Actor actor) {
        return writes.execute(
                actor.id(),
                "POST",
                "/api/v1/admin/announcements",
                key,
                body,
                () -> ResponseEntity.status(201).body(service.publish(body, actor)));
    }
}
