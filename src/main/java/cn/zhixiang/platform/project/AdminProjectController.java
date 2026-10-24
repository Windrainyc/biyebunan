package cn.zhixiang.platform.project;
import java.util.*;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import cn.zhixiang.platform.identity.Actor;
import cn.zhixiang.platform.shared.*;
/** admin 平台项目入口，数据范围取自服务端认证结果。 */
@RestController
@RequestMapping("/api/v1/admin/projects")
public class AdminProjectController {
 private final ProjectService service;private final IdempotentWrites writes;
 /** 初始化接口组件。
 * @param service 项目用例
 * @param writes 幂等写协调器 */
 public AdminProjectController(ProjectService service,IdempotentWrites writes){this.service=service;this.writes=writes;}
 /** 分页读取可见项目。
 * @param page 页码
 * @param size 页大小
 * @param q 搜索词
 * @param status 状态
 * @param sort 排序
 * @param actor 权威身份
 * @return 范围内分页 */
 @PreAuthorize("hasAuthority('get_admin_projects')")
 @GetMapping
 public PageResult<ProjectDtos.View> list(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size,@RequestParam(required=false) String q,@RequestParam(required=false) String status,@RequestParam(required=false) String sort,@AuthenticationPrincipal Actor actor){return service.list(new ProjectDtos.Query(page,size,q,status,sort),actor);}
 /** 获取可见项目。
 * @param id 项目ID
 * @param actor 权威身份
 * @return 响应和版本 */
 @PreAuthorize("hasAuthority('get_admin_projects_id')")
 @GetMapping("/{id}")
 public ResponseEntity<ProjectDtos.View> get(@PathVariable UUID id,@AuthenticationPrincipal Actor actor){var v=service.get(id,actor);return ResponseEntity.ok().eTag(Versions.etag(v.version())).body(v);}
 /** 在已确认订单下创建项目。
 * @param w 输入
 * @param key 幂等键
 * @param actor 身份
 * @return 201及项目 */
 @PreAuthorize("hasAuthority('post_admin_projects')")
 @PostMapping
 public ResponseEntity<String> create(@Valid @RequestBody ProjectDtos.Write w,@RequestHeader("Idempotency-Key") String key,@AuthenticationPrincipal Actor actor){return writes.execute(actor.id(),"POST","/api/v1/admin/projects",key,w,()->{var v=service.create(w,actor);return ResponseEntity.status(201).eTag(Versions.etag(v.version())).body(v);});}
 /** 编辑尚未验收完成的项目主体。
 * @param id 项目ID
 * @param w 新数据
 * @param match 版本
 * @param key 幂等键
 * @param actor 身份
 * @return 修改结果 */
 @PreAuthorize("hasAuthority('put_admin_projects_id')")
 @PutMapping("/{id}")
 public ResponseEntity<String> update(@PathVariable UUID id,@Valid @RequestBody ProjectDtos.Write w,@RequestHeader("If-Match") String match,@RequestHeader("Idempotency-Key") String key,@AuthenticationPrincipal Actor actor){long v=Versions.parse(match);return writes.execute(actor.id(),"PUT","/api/v1/admin/projects/"+id,key,List.of(v,w),()->{var result=service.update(id,v,w,actor);return ResponseEntity.ok().eTag(Versions.etag(result.version())).body(result);});}
}
