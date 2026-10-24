package cn.zhixiang.platform.order;
import java.util.*;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import cn.zhixiang.platform.identity.Actor;
import cn.zhixiang.platform.shared.*;
/** 财务权限隔离的人工收款入口。 */
@RestController
@RequestMapping("/api/v1/admin/orders")
public class ReceiptController {
 private final ReceiptService service;private final IdempotentWrites writes;
 /** 创建接口。
 * @param service 核验用例
 * @param writes 幂等事务 */
 public ReceiptController(ReceiptService service,IdempotentWrites writes){this.service=service;this.writes=writes;}
 /** 核验实际收款凭证，不调用外部支付。
 * @param id 订单
 * @param w 核验材料
 * @param match 版本
 * @param key 幂等键
 * @param actor 身份
 * @return 带新ETag的订单 */
 @PostMapping("/{id}/receipt") @PreAuthorize("hasAuthority('finance:confirm')")
 public ResponseEntity<String> confirm(@PathVariable UUID id,@Valid @RequestBody ReceiptDtos.Write w,@RequestHeader("If-Match") String match,@RequestHeader("Idempotency-Key") String key,@AuthenticationPrincipal Actor actor){long v=Versions.parse(match);return writes.execute(actor.id(),"POST","/api/v1/admin/orders/"+id+"/receipt",key,List.of(v,w),()->{var result=service.confirm(id,v,w,actor);return ResponseEntity.ok().eTag(Versions.etag(result.version())).body(result);});}
}
