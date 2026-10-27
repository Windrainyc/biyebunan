package cn.zhixiang.platform.asset;
import java.util.UUID;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import cn.zhixiang.platform.identity.Actor;
import cn.zhixiang.platform.shared.*;
/** 学生上传入口，只允许写入本人未锁定论坛帖，禁止写项目交付或收款证明。 */
@RestController @RequestMapping("/api/v1/student")
public class StudentUploadController {
 private final AssetService service;private final IdempotentWrites writes;
 /** 注入上传用例。
  * @param service 资产权限和隔离
  * @param writes 幂等事务 */
 public StudentUploadController(AssetService service,IdempotentWrites writes){this.service=service;this.writes=writes;}
 /** 创建签名上传。
 * @param w 材料
 * @param key 幂等键
 * @param actor 身份
 * @return 授权 */
 @PostMapping("/uploads") @PreAuthorize("hasAuthority('post_student_uploads')")
 public ResponseEntity<String> create(@Valid @RequestBody AssetDtos.Upload w,@RequestHeader("Idempotency-Key") String key,@AuthenticationPrincipal Actor actor){return writes.execute(actor.id(),"POST","/api/v1/student/uploads",key,w,()->ResponseEntity.status(201).body(service.create(w,actor)));}
 /** 提交校验任务。
 * @param id 上传会话
 * @param key 幂等键
 * @param actor 身份
 * @return 202隔离状态 */
 @PostMapping("/uploads/{id}/complete") @PreAuthorize("hasAuthority('post_student_uploads_id_complete')")
 public ResponseEntity<String> complete(@PathVariable UUID id,@RequestHeader("Idempotency-Key") String key,@AuthenticationPrincipal Actor actor){return writes.execute(actor.id(),"POST","/api/v1/student/uploads/"+id+"/complete",key,id,()->{var r=service.complete(id,actor);return ResponseEntity.status(202).eTag(Versions.etag(r.version())).body(r);});}
}
