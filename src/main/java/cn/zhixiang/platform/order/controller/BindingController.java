package cn.zhixiang.platform.order.controller;

import cn.zhixiang.platform.identity.model.Actor;
import cn.zhixiang.platform.order.dto.BindingDtos;
import cn.zhixiang.platform.order.service.BindingService;
import cn.zhixiang.platform.shared.dto.Versions;
import cn.zhixiang.platform.shared.exception.Errors;
import cn.zhixiang.platform.shared.service.IdempotentWrites;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 学生绑定订单，失败响应也在幂等事务提交后返回，避免验证码错误次数回滚。 */
@RestController
@RequestMapping("/api/v1/student/order-bindings")
public class BindingController {
    private final BindingService service;
    private final IdempotentWrites writes;

    /**
     * 创建接口组件。
     *
     * @param service 绑定用例
     * @param writes 幂等事务
     */
    public BindingController(BindingService service, IdempotentWrites writes) {
        this.service = service;
        this.writes = writes;
    }

    /**
     * 原子绑定或返回已保存的幂等结果。
     *
     * @param input 证明
     * @param key 幂等键
     * @param actor 服务端身份
     * @return 订单和ETag，或401失败结果
     */
    @PostMapping
    @PreAuthorize("hasAuthority('post_student_order_bindings')")
    public ResponseEntity<String> bind(
            @Valid @RequestBody BindingDtos.Write input,
            @RequestHeader("Idempotency-Key") String key,
            @AuthenticationPrincipal Actor actor) {
        return writes.execute(
                actor.id(),
                "POST",
                "/api/v1/student/order-bindings",
                key,
                input,
                () -> {
                    var order = service.bind(input, actor);
                    if (order == null)
                        return Errors.response(401, "VERIFICATION_INVALID", "验证材料无效、已使用或订单不可绑定");
                    return ResponseEntity.ok().eTag(Versions.etag(order.version())).body(order);
                });
    }
}
