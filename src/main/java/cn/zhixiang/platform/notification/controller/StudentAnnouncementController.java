package cn.zhixiang.platform.notification.controller;

import cn.zhixiang.platform.identity.model.Actor;
import cn.zhixiang.platform.notification.dto.AnnouncementDtos;
import cn.zhixiang.platform.notification.service.AnnouncementService;
import cn.zhixiang.platform.shared.service.IdempotentWrites;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** student端公告正文及受控发布入口。 */
@RestController
@RequestMapping("/api/v1/student/announcements")
public class StudentAnnouncementController {
    private final AnnouncementService service;
    private final IdempotentWrites writes;

    /**
     * 创建接口。
     *
     * @param service 公告用例
     * @param writes 幂等事务
     */
    public StudentAnnouncementController(AnnouncementService service, IdempotentWrites writes) {
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
    @PreAuthorize("hasAuthority('get_student_notifications')")
    public AnnouncementDtos.View get(@PathVariable UUID id, @AuthenticationPrincipal Actor actor) {
        return service.get(id, actor);
    }
}
