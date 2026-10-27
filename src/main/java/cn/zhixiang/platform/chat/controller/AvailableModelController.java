package cn.zhixiang.platform.chat.controller;

import cn.zhixiang.platform.chat.dto.AvailableModelResponse;
import cn.zhixiang.platform.chat.service.AvailableModelService;
import cn.zhixiang.platform.identity.model.Actor;
import cn.zhixiang.platform.shared.dto.PageQuery;
import cn.zhixiang.platform.shared.dto.PageResult;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 学生可用模型目录入口，禁止通过查询参数更换身份。 */
@RestController
public class AvailableModelController {
    private final AvailableModelService service;

    /**
     * 创建入口。
     *
     * @param service 模型目录用例
     */
    public AvailableModelController(AvailableModelService service) {
        this.service = service;
    }

    /**
     * 搜索可用模型，停用筛选返回空目录。
     *
     * @param page 页码，从1开始
     * @param size 每页1至100
     * @param q 名称或代码字面搜索
     * @param status ENABLED或DISABLED，可空
     * @param sort 白名单排序
     * @param actor 当前学生
     * @return 过滤后分页结果
     */
    @GetMapping("/api/v1/student/models")
    @PreAuthorize("hasAuthority('get_student_models')")
    public PageResult<AvailableModelResponse> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String sort,
            @AuthenticationPrincipal Actor actor) {
        return service.list(new PageQuery(page, size, q, status, sort), actor);
    }
}
