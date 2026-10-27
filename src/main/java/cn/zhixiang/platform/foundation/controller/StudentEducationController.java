package cn.zhixiang.platform.foundation.controller;

import cn.zhixiang.platform.foundation.dto.MajorDtos;
import cn.zhixiang.platform.foundation.dto.SchoolDtos;
import cn.zhixiang.platform.foundation.service.EducationDictionaryService;
import cn.zhixiang.platform.shared.dto.PageQuery;
import cn.zhixiang.platform.shared.dto.PageResult;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** student平台只读字典，平台鉴权由过滤器和方法权限共同执行。 */
@RestController
@RequestMapping("/api/v1/student/dictionaries")
public class StudentEducationController {
    private final EducationDictionaryService service;

    /**
     * 创建入口。
     *
     * @param service 字典用例
     */
    public StudentEducationController(EducationDictionaryService service) {
        this.service = service;
    }

    /**
     * 启用院校分页。
     *
     * @param page 页码
     * @param size 页大小
     * @param q 名称代码搜索
     * @param status 启停条件，停用返回空
     * @param sort 排序
     * @return 院校分页
     */
    @GetMapping("/schools")
    @PreAuthorize("hasAuthority('get_student_dictionaries_schools')")
    public PageResult<SchoolDtos.View> schools(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String sort) {
        return service.schools(new PageQuery(page, size, q, status, sort));
    }

    /**
     * 启用专业分页。
     *
     * @param page 页码
     * @param size 页大小
     * @param q 名称代码搜索
     * @param status 启停条件
     * @param sort 排序
     * @param schoolId 可选所属院校
     * @return 专业分页
     */
    @GetMapping("/majors")
    @PreAuthorize("hasAuthority('get_student_dictionaries_majors')")
    public PageResult<MajorDtos.View> majors(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) UUID schoolId) {
        return service.majors(new PageQuery(page, size, q, status, sort), schoolId);
    }
}
