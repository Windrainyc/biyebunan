package cn.zhixiang.platform.probing;
import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import cn.zhixiang.platform.identity.Actor;
import cn.zhixiang.platform.shared.IdempotentWrites;
/** 管理员配置探测入口，不在HTTP线程内调用外部网络。 */
@RestController @RequestMapping("/api/v1/admin")
public class ConfigurationProbeController {
 private final ConfigurationProbeService service;private final IdempotentWrites writes;
 /** 注入用例。
  * @param service 探测
  * @param writes 幂等事务 */
 public ConfigurationProbeController(ConfigurationProbeService service,IdempotentWrites writes){this.service=service;this.writes=writes;}
 /** 探测申请。
  * @param reason 必填原因，最多1000字 */
 public record Reason(@NotBlank @Size(max=1000) String reason){}
 /** 创建MODEL只读探测任务。
  * @param id 配置
  * @param request 原因
  * @param key 幂等键
  * @param actor 服务端身份
  * @return 202任务 */
 @PostMapping("/models/{id}/test") @PreAuthorize("hasAuthority('post_admin_models_id_test')")
 public ResponseEntity<String> models(@PathVariable UUID id,@Valid @RequestBody Reason request,@RequestHeader("Idempotency-Key") String key,@AuthenticationPrincipal Actor actor){return writes.execute(actor.id(),"POST","/api/v1/admin/models/"+id+"/test",key,request,()->ResponseEntity.accepted().body(service.create("MODEL",id,request.reason(),actor)));}
 /** 创建STORAGE只读探测任务。
  * @param id 配置
  * @param request 原因
  * @param key 幂等键
  * @param actor 服务端身份
  * @return 202任务 */
 @PostMapping("/storages/{id}/test") @PreAuthorize("hasAuthority('post_admin_storages_id_test')")
 public ResponseEntity<String> storages(@PathVariable UUID id,@Valid @RequestBody Reason request,@RequestHeader("Idempotency-Key") String key,@AuthenticationPrincipal Actor actor){return writes.execute(actor.id(),"POST","/api/v1/admin/storages/"+id+"/test",key,request,()->ResponseEntity.accepted().body(service.create("STORAGE",id,request.reason(),actor)));}
 /** 创建DOMAIN只读探测任务。
  * @param id 配置
  * @param request 原因
  * @param key 幂等键
  * @param actor 服务端身份
  * @return 202任务 */
 @PostMapping("/domains/{id}/test") @PreAuthorize("hasAuthority('post_admin_domains_id_test')")
 public ResponseEntity<String> domains(@PathVariable UUID id,@Valid @RequestBody Reason request,@RequestHeader("Idempotency-Key") String key,@AuthenticationPrincipal Actor actor){return writes.execute(actor.id(),"POST","/api/v1/admin/domains/"+id+"/test",key,request,()->ResponseEntity.accepted().body(service.create("DOMAIN",id,request.reason(),actor)));}
}
