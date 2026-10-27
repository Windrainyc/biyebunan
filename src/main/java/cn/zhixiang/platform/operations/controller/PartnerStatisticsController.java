package cn.zhixiang.platform.operations.controller;

import cn.zhixiang.platform.identity.model.Actor;
import cn.zhixiang.platform.operations.dto.StatisticsResponse;
import cn.zhixiang.platform.operations.service.StatisticsService;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

/** partner平台统计入口；范围由服务端身份确定。 */
@RestController
@RequestMapping("/api/v1/partner/statistics")
public class PartnerStatisticsController {
    private final StatisticsService service;

    /**
     * 注入聚合用例。
     *
     * @param service 查询服务
     */
    public PartnerStatisticsController(StatisticsService service) {
        this.service = service;
    }

    /**
     * 查询日期区间的统计。
     *
     * @param from 包含的首日
     * @param to 包含的末日
     * @param actor 当前身份
     * @return 不缓存的日统计
     */
    @GetMapping
    @PreAuthorize("hasAuthority('get_partner_statistics')")
    public ResponseEntity<StatisticsResponse> get(
            @RequestParam LocalDate from,
            @RequestParam LocalDate to,
            @AuthenticationPrincipal Actor actor) {
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .body(service.get(from, to, actor));
    }
}
