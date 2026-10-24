package cn.zhixiang.platform.project;
import java.util.*;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import cn.zhixiang.platform.identity.Actor;
import cn.zhixiang.platform.shared.*;
/** 管理员与学生交付入口，学生只能查看本人当前发布版本。 */
@RestController
@RequestMapping("/api/v1")
public class DeliveryController {
 private final DeliveryService service;private final IdempotentWrites writes;
 /** 创建接口。
 * @param service 交付用例
 * @param writes 幂等写 */
 public DeliveryController(DeliveryService service,IdempotentWrites writes){this.service=service;this.writes=writes;}
 /** 管理员分页。
 * @param id 项目
 * @param page 页码
 * @param size 数量
 * @param q 搜索
 * @param status 状态
 * @param sort 排序
 * @param actor 身份
 * @return 分页 */
 @GetMapping("/admin/projects/{id}/deliveries") @PreAuthorize("hasAuthority('get_admin_projects_id_deliveries')")
 public PageResult<DeliveryDtos.View> admin(@PathVariable UUID id,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size,@RequestParam(required=false) String q,@RequestParam(required=false) String status,@RequestParam(required=false) String sort,@AuthenticationPrincipal Actor actor){return service.list(id,new DeliveryDtos.Query(page,size,q,status,sort),actor);}
 /** 学生分页。
 * @param id 项目
 * @param page 页码
 * @param size 数量
 * @param q 搜索
 * @param status 状态
 * @param sort 排序
 * @param actor 身份
 * @return 已授权分页 */
 @GetMapping("/student/projects/{id}/deliveries") @PreAuthorize("hasAuthority('get_student_projects_id_deliveries')")
 public PageResult<DeliveryDtos.View> student(@PathVariable UUID id,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size,@RequestParam(required=false) String q,@RequestParam(required=false) String status,@RequestParam(required=false) String sort,@AuthenticationPrincipal Actor actor){return service.list(id,new DeliveryDtos.Query(page,size,q,status,sort),actor);}
 /** 创建版本。
 * @param id 项目
 * @param w 内容
 * @param key 幂等键
 * @param actor 身份
 * @return 201 */
 @PostMapping("/admin/projects/{id}/deliveries") @PreAuthorize("hasAuthority('post_admin_projects_id_deliveries')")
 public ResponseEntity<String> create(@PathVariable UUID id,@Valid @RequestBody DeliveryDtos.Write w,@RequestHeader("Idempotency-Key") String key,@AuthenticationPrincipal Actor actor){return writes.execute(actor.id(),"POST","/api/v1/admin/projects/"+id+"/deliveries",key,w,()->{var r=service.create(id,w,actor);return ResponseEntity.status(201).eTag(Versions.etag(r.version())).body(r);});}
 /** 发布。
 * @param id 交付
 * @param w 原因
 * @param match 版本
 * @param key 幂等键
 * @param actor 身份
 * @return 交付 */
 @PostMapping("/admin/deliveries/{id}/publish") @PreAuthorize("hasAuthority('post_admin_deliveries_id_publish')")
 public ResponseEntity<String> publish(@PathVariable UUID id,@Valid @RequestBody DeliveryDtos.Reason w,@RequestHeader("If-Match") String match,@RequestHeader("Idempotency-Key") String key,@AuthenticationPrincipal Actor actor){return change(id,w,match,key,actor,true);}
 /** 撤回。
 * @param id 交付
 * @param w 原因
 * @param match 版本
 * @param key 幂等键
 * @param actor 身份
 * @return 交付 */
 @PostMapping("/admin/deliveries/{id}/withdraw") @PreAuthorize("hasAuthority('post_admin_deliveries_id_withdraw')")
 public ResponseEntity<String> withdraw(@PathVariable UUID id,@Valid @RequestBody DeliveryDtos.Reason w,@RequestHeader("If-Match") String match,@RequestHeader("Idempotency-Key") String key,@AuthenticationPrincipal Actor actor){return change(id,w,match,key,actor,false);}
 private ResponseEntity<String> change(UUID id,DeliveryDtos.Reason w,String match,String key,Actor actor,boolean publish){long version=Versions.parse(match);String path="/api/v1/admin/deliveries/"+id+(publish?"/publish":"/withdraw");return writes.execute(actor.id(),"POST",path,key,List.of(version,w),()->{var r=service.change(id,version,publish,w.reason(),actor);return ResponseEntity.ok().eTag(Versions.etag(r.version())).body(r);});}
}
