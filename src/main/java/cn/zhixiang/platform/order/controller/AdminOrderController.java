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

/** admin 平台订单入口，数据范围取自服务端认证结果。 */
@RestController
@RequestMapping("/api/v1/admin/orders")
public class AdminOrderController {
    private final OrderService service;
    private final IdempotentWrites writes;

    /**
     * 初始化接口组件。
     *
     * @param service 订单用例
     * @param writes 幂等写协调器
     */
    public AdminOrderController(OrderService service, IdempotentWrites writes) {
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
    @PreAuthorize("hasAuthority('get_admin_orders')")
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
    @PreAuthorize("hasAuthority('get_admin_orders_id')")
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
    @PreAuthorize("hasAuthority('post_admin_orders')")
    @PostMapping
    public ResponseEntity<String> create(
            @Valid @RequestBody OrderDtos.Write w,
            @RequestHeader("Idempotency-Key") String key,
            @AuthenticationPrincipal Actor actor) {
        return writes.execute(
                actor.id(),
                "POST",
                "/api/v1/admin/orders",
                key,
                w,
                () -> {
                    var v = service.create(w, actor);
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
    @PreAuthorize("hasAuthority('put_admin_orders_id')")
    @PutMapping("/{id}")
    public ResponseEntity<String> update(
            @PathVariable UUID id,
            @Valid @RequestBody OrderDtos.Write w,
            @RequestHeader("If-Match") String match,
            @RequestHeader("Idempotency-Key") String key,
            @AuthenticationPrincipal Actor actor) {
        long v = Versions.parse(match);
        return writes.execute(
                actor.id(),
                "PUT",
                "/api/v1/admin/orders/" + id,
                key,
                List.of(v, w),
                () -> {
                    var result = service.update(id, v, w, actor);
                    return ResponseEntity.ok().eTag(Versions.etag(result.version())).body(result);
                });
    }

    /**
     * 调整中介归属。
     *
     * @param id 订单ID
     * @param w 新归属
     * @param match 版本
     * @param key 幂等键
     * @param actor 身份
     * @return 修改结果
     */
    @PreAuthorize("hasAuthority('post_admin_orders_id_assignment')")
    @PostMapping("/{id}/assignment")
    public ResponseEntity<String> assign(
            @PathVariable UUID id,
            @Valid @RequestBody OrderDtos.Assignment w,
            @RequestHeader("If-Match") String match,
            @RequestHeader("Idempotency-Key") String key,
            @AuthenticationPrincipal Actor actor) {
        long v = Versions.parse(match);
        return writes.execute(
                actor.id(),
                "POST",
                "/api/v1/admin/orders/" + id + "/assignment",
                key,
                List.of(v, w),
                () -> {
                    var result = service.assign(id, v, w, actor);
                    return ResponseEntity.ok().eTag(Versions.etag(result.version())).body(result);
                });
    }

    /**
     * 按业务状态机核实与流转订单。
     *
     * @param id 订单
     * @param w 动作
     * @param match 期望版本
     * @param key 幂等键
     * @param actor 管理员
     * @return 订单
     */
    @PreAuthorize("hasAuthority('post_admin_orders_id_transition')")
    @PostMapping("/{id}/transition")
    public ResponseEntity<String> transition(
            @PathVariable UUID id,
            @Valid @RequestBody OrderDtos.Transition w,
            @RequestHeader("If-Match") String match,
            @RequestHeader("Idempotency-Key") String key,
            @AuthenticationPrincipal Actor actor) {
        long v = Versions.parse(match);
        return writes.execute(
                actor.id(),
                "POST",
                "/api/v1/admin/orders/" + id + "/transition",
                key,
                List.of(v, w),
                () -> {
                    var result = service.transition(id, v, w, actor);
                    return ResponseEntity.ok().eTag(Versions.etag(result.version())).body(result);
                });
    }
}
