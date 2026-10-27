package cn.zhixiang.platform.chat.controller;

import cn.zhixiang.platform.chat.dto.MessageResponse;
import cn.zhixiang.platform.chat.service.MessageService;
import cn.zhixiang.platform.identity.model.Actor;
import cn.zhixiang.platform.shared.dto.PageQuery;
import cn.zhixiang.platform.shared.dto.PageResult;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** 学生历史消息查询。 */
@RestController
public class MessageController {
    private final MessageService service;

    /**
     * 创建接口。
     *
     * @param service 消息用例
     */
    public MessageController(MessageService service) {
        this.service = service;
    }

    /**
     * 当前授权会话的消息分页。
     *
     * @param id 会话
     * @param page 页码
     * @param size 数量
     * @param q 正文关键字
     * @param sort 白名单排序
     * @param status 不支持
     * @param actor 当前身份
     * @return 分页
     */
    @GetMapping("/api/v1/student/conversations/{id}/messages")
    @PreAuthorize("hasAuthority('get_student_conversations_id_messages')")
    public PageResult<MessageResponse> history(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String status,
            @AuthenticationPrincipal Actor actor) {
        return service.history(id, new PageQuery(page, size, q, status, sort), actor);
    }
}
