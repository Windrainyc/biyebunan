package cn.zhixiang.platform.forum.controller;

import cn.zhixiang.platform.forum.dto.ForumCommunityDtos;
import cn.zhixiang.platform.forum.dto.ForumFeedResponse;
import cn.zhixiang.platform.forum.service.ForumCommunityService;
import cn.zhixiang.platform.identity.model.Actor;
import cn.zhixiang.platform.shared.dto.*;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** 学生论坛发现与社区聚合入口。 */
@RestController
@RequestMapping("/api/v1/student/forum")
public class StudentForumCommunityController {
    private final ForumCommunityService service;

    /**
     * 读取或初始化论坛社区数据。
     *
     * @param service 社区用例
     */
    public StudentForumCommunityController(ForumCommunityService service) {
        this.service = service;
    }

    /**
     * 真实分页及筛选。
     *
     * @param page 页码
     * @param size 页大小
     * @param q 关键词
     * @param boardId 板块标识
     * @param scope 个人范围
     * @param mode 排序模式
     * @param actor 登录身份
     * @return 当前页
     */
    @GetMapping("/feed")
    @PreAuthorize("hasAuthority('get_student_posts')")
    public PageResult<ForumFeedResponse> feed(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) UUID boardId,
            @RequestParam(defaultValue = "all") String scope,
            @RequestParam(defaultValue = "latest") String mode,
            @AuthenticationPrincipal Actor actor) {
        return service.feed(new PageQuery(page, size, q, null, null), boardId, scope, mode, actor);
    }

    /**
     * 读取或初始化论坛社区数据。
     *
     * @param actor 登录身份
     * @return 公开社区热度
     */
    @GetMapping("/community")
    @PreAuthorize("hasAuthority('get_student_posts')")
    public ForumCommunityDtos.Summary summary(@AuthenticationPrincipal Actor actor) {
        return service.summary(actor);
    }
}
