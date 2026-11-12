package cn.zhixiang.platform.promotion.controller;

import cn.zhixiang.platform.promotion.dto.BannerDtos;
import cn.zhixiang.platform.promotion.service.BannerService;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** 学生首页横幅，只公开已启用内容。 */
@RestController
@RequestMapping("/api/v1/student/banners")
public class StudentBannerController {
    private final BannerService service;

    /**
     * 注入服务。
     *
     * @param service 横幅用例
     */
    public StudentBannerController(BannerService service) {
        this.service = service;
    }

    /**
     * 返回当前启用横幅。
     *
     * @return 最多十条横幅
     */
    @GetMapping
    @PreAuthorize("hasAuthority('get_student_banners')")
    public java.util.List<BannerDtos.View> list() {
        return service.active();
    }
}
