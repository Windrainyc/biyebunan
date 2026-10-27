package cn.zhixiang.platform.knowledge.controller;

import cn.zhixiang.platform.knowledge.dto.CategoryDtos;
import cn.zhixiang.platform.knowledge.dto.TagDtos;
import cn.zhixiang.platform.knowledge.service.CategoryService;
import cn.zhixiang.platform.knowledge.service.TagService;
import cn.zhixiang.platform.shared.dto.PageQuery;
import cn.zhixiang.platform.shared.dto.PageResult;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 学生端只读知识字典，只返回可用于新关联的启用项。 */
@RestController
@RequestMapping("/api/v1/student/dictionaries")
public class TaxonomyDictionaryController {
    private final CategoryService categories;
    private final TagService tags;

    /**
     * 初始化模块组件，所需端口和配置由构造器注入。
     *
     * @param categories 分类用例
     * @param tags 标签用例
     */
    public TaxonomyDictionaryController(CategoryService categories, TagService tags) {
        this.categories = categories;
        this.tags = tags;
    }

    /**
     * 返回启用分类页。
     *
     * @param page 页码
     * @param size 每页数
     * @param q 搜索
     * @param status 启停过滤；请求停用返回空
     * @param sort 时间排序
     * @return 启用分类页
     */
    @GetMapping("/categories")
    @PreAuthorize("hasAuthority('get_student_dictionaries_categories')")
    public PageResult<CategoryDtos.View> categories(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String sort) {
        var input = new PageQuery(page, size, q, status, sort);
        return "DISABLED".equals(input.status())
                ? new PageResult<>(List.of(), page, size, 0)
                : categories.list(new PageQuery(page, size, q, "ENABLED", sort));
    }

    /**
     * 返回启用标签页。
     *
     * @param page 页码
     * @param size 每页数
     * @param q 搜索
     * @param status 启停过滤；请求停用返回空
     * @param sort 时间排序
     * @return 启用标签页
     */
    @GetMapping("/tags")
    @PreAuthorize("hasAuthority('get_student_dictionaries_tags')")
    public PageResult<TagDtos.View> tags(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String sort) {
        var input = new PageQuery(page, size, q, status, sort);
        return "DISABLED".equals(input.status())
                ? new PageResult<>(List.of(), page, size, 0)
                : tags.list(new PageQuery(page, size, q, "ENABLED", sort));
    }
}
