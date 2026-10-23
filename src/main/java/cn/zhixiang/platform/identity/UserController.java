package cn.zhixiang.platform.identity;
import cn.zhixiang.platform.shared.*;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.ResponseEntity;
/** 管理端账户接口；无物理删除入口，保留历史交付与审计关系。 */
@RestController
@RequestMapping("/api/v1/admin/users")
public class UserController {
 private final UserService users;private final IdempotentWrites writes;
 /** @param users 用户用例
  * @param writes 幂等事务 */
 public UserController(UserService users,IdempotentWrites writes){this.users=users;this.writes=writes;}
 /** @param page 页码
  * @param size 页大小
  * @param q 昵称搜索
  * @param status 状态
  * @param sort 排序
  * @param platform 平台
  * @param roleId 角色
  * @return 脱敏用户页 */
 @GetMapping @PreAuthorize("hasAuthority('get_admin_users')")
 public PageResult<AuthDtos.User> list(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size,@RequestParam(required=false) String q,@RequestParam(required=false) String status,@RequestParam(required=false) String sort,@RequestParam(required=false) String platform,@RequestParam(required=false) UUID roleId){return users.list(new PageQuery(page,size,q,status,sort),platform,roleId);}
 /** @param id 用户
  * @return 详情与ETag */
 @GetMapping("/{id}") @PreAuthorize("hasAuthority('get_admin_users_id')")
 public ResponseEntity<AuthDtos.User> get(@PathVariable UUID id){var v=users.get(id);return ResponseEntity.ok().eTag(Versions.etag(v.version())).body(v);}
 /** @param w 待激活账户
  * @param key 幂等键
  * @param actor 当前管理员
  * @return 201账户 */
 @PostMapping @PreAuthorize("hasAuthority('post_admin_users')")
 public ResponseEntity<String> create(@Valid @RequestBody UserDtos.Create w,@RequestHeader("Idempotency-Key") String key,@AuthenticationPrincipal Actor actor){return writes.execute(actor.id(),"POST","/api/v1/admin/users",key,w,()->{var v=users.create(w,actor.id());return ResponseEntity.status(201).eTag(Versions.etag(v.version())).body(v);});}
 /** @param id 用户
  * @param w 完整新资料
  * @param match 版本
  * @param key 幂等键
  * @param actor 当前管理员
  * @return 更新结果 */
 @PutMapping("/{id}") @PreAuthorize("hasAuthority('put_admin_users_id')")
 public ResponseEntity<String> update(@PathVariable UUID id,@Valid @RequestBody UserDtos.Write w,@RequestHeader("If-Match") String match,@RequestHeader("Idempotency-Key") String key,@AuthenticationPrincipal Actor actor){long v=Versions.parse(match);return writes.execute(actor.id(),"PUT","/api/v1/admin/users/"+id,key,List.of(v,w),()->{var r=users.update(id,v,w,actor.id());return ResponseEntity.ok().eTag(Versions.etag(r.version())).body(r);});}
 /** @param id 用户
  * @param state 启停
  * @param match 版本
  * @param key 幂等键
  * @param actor 当前管理员
  * @return 启停结果 */
 @PostMapping("/{id}/status") @PreAuthorize("hasAuthority('post_admin_users_id_status')")
 public ResponseEntity<String> status(@PathVariable UUID id,@Valid @RequestBody RoleDtos.State state,@RequestHeader("If-Match") String match,@RequestHeader("Idempotency-Key") String key,@AuthenticationPrincipal Actor actor){long v=Versions.parse(match);return writes.execute(actor.id(),"POST","/api/v1/admin/users/"+id+"/status",key,List.of(v,state),()->{var r=users.status(id,v,state.status(),actor.id());return ResponseEntity.ok().eTag(Versions.etag(r.version())).body(r);});}
}
