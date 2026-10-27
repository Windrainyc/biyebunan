package cn.zhixiang.platform.billing.controller;

import cn.zhixiang.platform.billing.dto.UsageQuery;
import cn.zhixiang.platform.billing.dto.UsageResponse;
import cn.zhixiang.platform.billing.service.UsageService;
import cn.zhixiang.platform.identity.model.Actor;
import cn.zhixiang.platform.shared.dto.PageResult;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

/** 学生历史用量入口，数据范围只取已认证用户。 */
@RestController
public class UsageController {
    private final UsageService service;

    /**
     * 创建入口。
     *
     * @param service 用量用例
     */
    public UsageController(UsageService service) {
        this.service = service;
    }

    /**
     * 分页查看本人用量。
     *
     * @param page 页码
     * @param size 每页数
     * @param q 运行ID字面搜索
     * @param status 账务状态
     * @param sort 排序
     * @param from UTC起始日期，包含
     * @param to UTC结束日期，包含
     * @param actor 学生身份
     * @return 账单分页
     */
    @GetMapping("/api/v1/student/usage")
    @PreAuthorize("hasAuthority('get_student_usage')")
    public PageResult<UsageResponse> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate to,
            @AuthenticationPrincipal Actor actor) {
        return service.list(new UsageQuery(page, size, q, status, sort, from, to), actor);
    }
}
