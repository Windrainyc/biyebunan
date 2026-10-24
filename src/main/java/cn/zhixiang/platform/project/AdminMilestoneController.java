package cn.zhixiang.platform.project;
import java.util.*;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import cn.zhixiang.platform.identity.Actor;
import cn.zhixiang.platform.shared.*;
/** 管理员里程碑入口。 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminMilestoneController {
 private final MilestoneService service;private final IdempotentWrites writes;
 /** 构造接口。
 * @param service 用例
 * @param writes 幂等事务 */
 public AdminMilestoneController(MilestoneService service,IdempotentWrites writes){this.service=service;this.writes=writes;}
 /** 查询项目里程碑。
 * @param id 项目
 * @param page 页码
 * @param size 页大小
 * @param q 名称
 * @param sort 排序
 * @param actor 身份
 * @return 分页 */
 @PreAuthorize("hasAuthority('get_admin_projects_id_milestones')")
 @GetMapping("/projects/{id}/milestones")
 public PageResult<MilestoneDtos.View> list(@PathVariable UUID id,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size,@RequestParam(required=false) String q,@RequestParam(required=false) String sort,@AuthenticationPrincipal Actor actor){return service.list(id,new PageQuery(page,size,q,null,sort),actor);}
 /** 创建里程碑。
 * @param id 项目
 * @param w 输入
 * @param key 幂等键
 * @param actor 身份
 * @return 201 */
 @PreAuthorize("hasAuthority('post_admin_projects_id_milestones')")
 @PostMapping("/projects/{id}/milestones")
 public ResponseEntity<String> create(@PathVariable UUID id,@Valid @RequestBody MilestoneDtos.Write w,@RequestHeader("Idempotency-Key") String key,@AuthenticationPrincipal Actor actor){return writes.execute(actor.id(),"POST","/api/v1/admin/projects/"+id+"/milestones",key,w,()->{var v=service.create(id,w,actor);return ResponseEntity.status(201).eTag(Versions.etag(v.version())).body(v);});}
 /** 更新里程碑。
 * @param id 里程碑
 * @param w 输入
 * @param match 版本
 * @param key 幂等键
 * @param actor 身份
 * @return 修改结果 */
 @PreAuthorize("hasAuthority('put_admin_milestones_id')")
 @PutMapping("/milestones/{id}")
 public ResponseEntity<String> update(@PathVariable UUID id,@Valid @RequestBody MilestoneDtos.Write w,@RequestHeader("If-Match") String match,@RequestHeader("Idempotency-Key") String key,@AuthenticationPrincipal Actor actor){long v=Versions.parse(match);return writes.execute(actor.id(),"PUT","/api/v1/admin/milestones/"+id,key,List.of(v,w),()->{var result=service.update(id,v,w,actor);return ResponseEntity.ok().eTag(Versions.etag(result.version())).body(result);});}
 /** 删除里程碑。
 * @param id 里程碑
 * @param match 版本
 * @param key 幂等键
 * @param actor 身份
 * @return 204 */
 @PreAuthorize("hasAuthority('delete_admin_milestones_id')")
 @DeleteMapping("/milestones/{id}")
 public ResponseEntity<String> delete(@PathVariable UUID id,@RequestHeader("If-Match") String match,@RequestHeader("Idempotency-Key") String key,@AuthenticationPrincipal Actor actor){long v=Versions.parse(match);return writes.execute(actor.id(),"DELETE","/api/v1/admin/milestones/"+id,key,v,()->{service.delete(id,v,actor);return ResponseEntity.noContent().build();});}
}
