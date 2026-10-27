package cn.zhixiang.platform.order.controller;

import cn.zhixiang.platform.identity.model.Actor;
import cn.zhixiang.platform.order.dto.OrderDtos;
import cn.zhixiang.platform.order.service.OrderService;
import cn.zhixiang.platform.shared.dto.PageResult;
import cn.zhixiang.platform.shared.dto.Versions;
import cn.zhixiang.platform.shared.service.IdempotentWrites;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/** partner 平台订单入口，数据范围取自服务端认证结果。 */
@RestController
@RequestMapping("/api/v1/partner/orders")
public class PartnerOrderController {
    private final OrderService service;
    private final IdempotentWrites writes;

    /**
     * 初始化接口组件。
     *
     * @param service 订单用例
     * @param writes 幂等写协调器
     */
    public PartnerOrderController(OrderService service, IdempotentWrites writes) {
        this.service = service;
        this.writes = writes;
    }

    /**
     * 分页读取可见订单。
     *
     * @param page 页码
     * @param size 页大小
     * @param q 搜索词
     * @param status 状态
     * @param sort 排序
     * @param actor 权威身份
     * @return 范围内分页
     */
    @PreAuthorize("hasAuthority('get_partner_orders')")
    @GetMapping
    public PageResult<OrderDtos.View> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String sort,
            @AuthenticationPrincipal Actor actor) {
        return service.list(new OrderDtos.Query(page, size, q, status, sort), actor);
    }

    /**
     * 获取可见订单。
     *
     * @param id 订单ID
     * @param actor 权威身份
     * @return 响应和版本
     */
    @PreAuthorize("hasAuthority('get_partner_orders_id')")
    @GetMapping("/{id}")
    public ResponseEntity<OrderDtos.View> get(
            @PathVariable UUID id, @AuthenticationPrincipal Actor actor) {
        var v = service.get(id, actor);
        return ResponseEntity.ok().eTag(Versions.etag(v.version())).body(v);
    }

    /**
     * 创建草稿。
     *
     * @param w 输入
     * @param key 幂等键
     * @param actor 身份
     * @return 201及订单
     */
    @PreAuthorize("hasAuthority('post_partner_orders')")
    @PostMapping
    public ResponseEntity<String> create(
            @Valid @RequestBody OrderDtos.PartnerWrite w,
            @RequestHeader("Idempotency-Key") String key,
            @AuthenticationPrincipal Actor actor) {
        return writes.execute(
                actor.id(),
                "POST",
                "/api/v1/partner/orders",
                key,
                w,
                () -> {
                    var v = service.partnerCreate(w, actor);
                    return ResponseEntity.status(201).eTag(Versions.etag(v.version())).body(v);
                });
    }

    /**
     * 编辑未确认草稿。
     *
     * @param id 订单ID
     * @param w 新数据
     * @param match 版本
     * @param key 幂等键
     * @param actor 身份
     * @return 修改结果
     */
    @PreAuthorize("hasAuthority('patch_partner_orders_id')")
    @PatchMapping("/{id}")
    public ResponseEntity<String> update(
            @PathVariable UUID id,
            @Valid @RequestBody OrderDtos.PartnerUpdate w,
            @RequestHeader("If-Match") String match,
            @RequestHeader("Idempotency-Key") String key,
            @AuthenticationPrincipal Actor actor) {
        long v = Versions.parse(match);
        return writes.execute(
                actor.id(),
                "PATCH",
                "/api/v1/partner/orders/" + id,
                key,
                List.of(v, w),
                () -> {
                    var result = service.partnerUpdate(id, v, w, actor);
                    return ResponseEntity.ok().eTag(Versions.etag(result.version())).body(result);
                });
    }
}
