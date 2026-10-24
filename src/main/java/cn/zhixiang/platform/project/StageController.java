package cn.zhixiang.platform.project;
import java.util.*;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import cn.zhixiang.platform.identity.Actor;
import cn.zhixiang.platform.shared.*;
/** 独立平台的阶段和验收入口。 */
@RestController
@RequestMapping("/api/v1")
public class StageController {
 private final StageService service;private final IdempotentWrites writes;
 /** 初始化。
 * @param service 状态机
 * @param writes 幂等写 */
 public StageController(StageService service,IdempotentWrites writes){this.service=service;this.writes=writes;}
 /** 管理员变更阶段。
 * @param id 项目
 * @param w 输入
 * @param match 版本
 * @param key 幂等键
 * @param actor 身份
 * @return 项目 */
 @PostMapping("/admin/projects/{id}/stage") @PreAuthorize("hasAuthority('post_admin_projects_id_stage')")
 public ResponseEntity<String> stage(@PathVariable UUID id,@Valid @RequestBody StageDtos.Change w,@RequestHeader("If-Match") String match,@RequestHeader("Idempotency-Key") String key,@AuthenticationPrincipal Actor actor){long v=Versions.parse(match);return writes.execute(actor.id(),"POST","/api/v1/admin/projects/"+id+"/stage",key,List.of(v,w),()->{var r=service.change(id,v,w,actor);return ResponseEntity.ok().eTag(Versions.etag(r.version())).body(r);});}
 /** 学生验收本人项目。
 * @param id 项目
 * @param w 决定
 * @param match 版本
 * @param key 幂等键
 * @param actor 身份
 * @return 项目 */
 @PostMapping("/student/projects/{id}/acceptance") @PreAuthorize("hasAuthority('post_student_projects_id_acceptance')")
 public ResponseEntity<String> acceptance(@PathVariable UUID id,@Valid @RequestBody StageDtos.Acceptance w,@RequestHeader("If-Match") String match,@RequestHeader("Idempotency-Key") String key,@AuthenticationPrincipal Actor actor){long v=Versions.parse(match);return writes.execute(actor.id(),"POST","/api/v1/student/projects/"+id+"/acceptance",key,List.of(v,w),()->{var r=service.accept(id,v,w,actor);return ResponseEntity.ok().eTag(Versions.etag(r.version())).body(r);});}
}
