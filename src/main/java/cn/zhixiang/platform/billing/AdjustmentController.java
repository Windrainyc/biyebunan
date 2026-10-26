package cn.zhixiang.platform.billing;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import cn.zhixiang.platform.identity.Actor;
import cn.zhixiang.platform.shared.IdempotentWrites;
/** 人工账务调整入口，幂等键防止重复提交再次扣退。 */
@RestController
public class AdjustmentController {
 private final AdjustmentService service;private final IdempotentWrites writes;
 /** 创建入口。
  * @param service 调整用例
  * @param writes 幂等事务 */
 public AdjustmentController(AdjustmentService service,IdempotentWrites writes){this.service=service;this.writes=writes;}
 /** 追加不可变调整单，没有可更新版本，不接受覆盖旧账单。
  * @param w 输入
  * @param key 幂等键
  * @param actor 管理员
  * @return 201调整单或幂等重放 */
 @PostMapping("/api/v1/admin/usage-adjustments") @PreAuthorize("hasAuthority('billing:adjust')")
 public ResponseEntity<String> create(@Valid @RequestBody AdjustmentDtos.Write w,@RequestHeader("Idempotency-Key") String key,@AuthenticationPrincipal Actor actor){return writes.execute(actor.id(),"POST","/api/v1/admin/usage-adjustments",key,w,()->ResponseEntity.status(201).body(service.create(w,actor)));}
}
