package cn.zhixiang.platform.operations;
import java.util.*;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import cn.zhixiang.platform.identity.Actor;
import cn.zhixiang.platform.shared.*;
/** 管理端任务监控与恢复入口。 */
@RestController
@RequestMapping("/api/v1/admin/jobs")
public class JobController {
 private final JobService service;private final IdempotentWrites writes;
 /** 创建接口。
 * @param service 任务用例
 * @param writes 幂等写 */
 public JobController(JobService service,IdempotentWrites writes){this.service=service;this.writes=writes;}
 /** 任务分页。
 * @param page 页码
 * @param size 页大小
 * @param q 搜索
 * @param status 状态
 * @param sort 排序
 * @return 分页 */
 @GetMapping @PreAuthorize("hasAuthority('get_admin_jobs')")
 public PageResult<JobDtos.View> list(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size,@RequestParam(required=false) String q,@RequestParam(required=false) String status,@RequestParam(required=false) String sort){return service.list(new JobDtos.Query(page,size,q,status,sort));}
 /** 任务详情。
 * @param id ID
 * @return 任务及ETag */
 @GetMapping("/{id}") @PreAuthorize("hasAuthority('get_admin_jobs_id')")
 public ResponseEntity<JobDtos.View> get(@PathVariable UUID id){var r=service.get(id);return ResponseEntity.ok().eTag(Versions.etag(r.version())).body(r);}
 /** 恢复失败任务。
 * @param id 任务
 * @param w 原因
 * @param match 版本
 * @param key 幂等键
 * @param actor 操作者
 * @return 202 */
 @PostMapping("/{id}/retry") @PreAuthorize("hasAuthority('post_admin_jobs_id_retry')")
 public ResponseEntity<String> retry(@PathVariable UUID id,@Valid @RequestBody JobDtos.Retry w,@RequestHeader("If-Match") String match,@RequestHeader("Idempotency-Key") String key,@AuthenticationPrincipal Actor actor){long v=Versions.parse(match);return writes.execute(actor.id(),"POST","/api/v1/admin/jobs/"+id+"/retry",key,List.of(v,w),()->{var r=service.retry(id,v,w.reason(),actor.id());return ResponseEntity.accepted().eTag(Versions.etag(r.version())).body(r);});}
}
