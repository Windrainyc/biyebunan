package cn.zhixiang.platform.chat.controller;

import cn.zhixiang.platform.chat.dto.ConversationDtos;
import cn.zhixiang.platform.chat.dto.MessageResponse;
import cn.zhixiang.platform.chat.service.ChatAuditService;
import cn.zhixiang.platform.identity.model.Actor;
import cn.zhixiang.platform.shared.dto.PageQuery;
import cn.zhixiang.platform.shared.dto.PageResult;

import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** 管理员审计入口，响应禁止缓存；中介不能进入。 */
@RestController
@RequestMapping("/api/v1/admin/conversations")
public class ChatAuditController {
    private final ChatAuditService service;

    /**
     * 创建入口。
     *
     * @param service 审计用例
     */
    public ChatAuditController(ChatAuditService service) {
        this.service = service;
    }

    /**
     * 审计目录。
     *
     * @param page 页码
     * @param size 页大小
     * @param q 标题搜索
     * @param status 不支持，传入拒绝
     * @param sort 排序
     * @param reason 必填理由
     * @param actor 管理员
     * @return 不缓存的目录
     */
    @GetMapping
    @PreAuthorize("hasAuthority('chat:audit')")
    public ResponseEntity<PageResult<ConversationDtos.View>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String sort,
            @RequestParam String reason,
            @AuthenticationPrincipal Actor actor) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(
                        service.conversations(
                                new PageQuery(page, size, q, status, sort), reason, actor));
    }

    /**
     * 审计具体会话消息。
     *
     * @param id 会话
     * @param page 页码
     * @param size 页大小
     * @param q 正文搜索
     * @param status 不支持
     * @param sort 排序
     * @param reason 必填理由
     * @param actor 管理员
     * @return 不缓存的历史
     */
    @GetMapping("/{id}/messages")
    @PreAuthorize("hasAuthority('chat:audit')")
    public ResponseEntity<PageResult<MessageResponse>> messages(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String sort,
            @RequestParam String reason,
            @AuthenticationPrincipal Actor actor) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(
                        service.messages(
                                id, new PageQuery(page, size, q, status, sort), reason, actor));
    }
}
