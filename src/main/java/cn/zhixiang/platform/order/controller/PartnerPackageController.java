package cn.zhixiang.platform.order.controller;

import cn.zhixiang.platform.order.dto.ServicePackageDtos;
import cn.zhixiang.platform.order.service.ServicePackageService;
import cn.zhixiang.platform.shared.dto.*;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** 中介下单时可读取启用套餐，不能维护配置。 */
@RestController
@RequestMapping("/api/v1/partner/service-packages")
public class PartnerPackageController {
    private final ServicePackageService service;

    /**
     * 初始化业务组件。
     *
     * @param service 套餐服务
     */
    public PartnerPackageController(ServicePackageService service) {
        this.service = service;
    }

    /**
     * 搜索启用套餐。
     *
     * @param page 页码
     * @param size 每页数量
     * @param q 搜索
     * @return 启用套餐
     */
    @GetMapping
    @PreAuthorize("hasAuthority('post_partner_orders')")
    public PageResult<ServicePackageDtos.View> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String q) {
        return service.list(new PageQuery(page, size, q, "ENABLED", null));
    }
}
