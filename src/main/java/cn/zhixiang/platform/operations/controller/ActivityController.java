package cn.zhixiang.platform.operations.controller;

import cn.zhixiang.platform.identity.model.Actor;
import cn.zhixiang.platform.operations.dto.ActivityDtos;
import cn.zhixiang.platform.operations.service.ActivityService;
import cn.zhixiang.platform.shared.dto.PageResult;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.UUID;

/** 运营活动入口，管理员独立查询权限，不向中介暴露学生浏览记录。 */
@RestController
@RequestMapping("/api/v1")
public class ActivityController {
    private final ActivityService service;

    /**
     * 初始化业务组件。
     *
     * @param service 活动用例
     */
    public ActivityController(ActivityService service) {
        this.service = service;
    }

    /**
     * 写入当前账户活动。
     *
     * @param w 安全片段
     * @param actor 身份
     * @param request 来源请求
     */
    @PostMapping({"/admin/activity", "/student/activity", "/partner/activity"})
    @PreAuthorize("isAuthenticated()")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void record(
            @Valid @RequestBody ActivityDtos.Write w,
            @AuthenticationPrincipal Actor actor,
            HttpServletRequest request) {
        service.record(w, actor, request.getRemoteAddr(), request.getRequestURI().split("/")[3]);
    }

    /**
     * 分页查询埋点。
     *
     * @param from 开始日
     * @param to 结束日
     * @param userId 用户筛选
     * @param kind 事件类型
     * @param page 页码
     * @param size 页大小
     * @return 分页记录
     */
    @GetMapping("/admin/activity")
    @PreAuthorize("hasAuthority('activity:read')")
    public PageResult<ActivityDtos.View> list(
            @RequestParam LocalDate from,
            @RequestParam LocalDate to,
            @RequestParam(required = false) UUID userId,
            @RequestParam(required = false) String kind,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.list(from, to, userId, kind, page, size);
    }

    /**
     * 查询自然日统计。
     *
     * @param from 开始日
     * @param to 结束日
     * @param userId 可选用户
     * @param kind 可选类型
     * @return 日汇总
     */
    @GetMapping("/admin/activity/summary")
    @PreAuthorize("hasAuthority('activity:read')")
    public java.util.List<ActivityDtos.Daily> daily(
            @RequestParam LocalDate from,
            @RequestParam LocalDate to,
            @RequestParam(required = false) UUID userId,
            @RequestParam(required = false) String kind) {
        return service.daily(from, to, userId, kind);
    }
}
