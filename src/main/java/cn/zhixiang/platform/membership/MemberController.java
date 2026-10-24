package cn.zhixiang.platform.membership;
import cn.zhixiang.platform.shared.*;
import cn.zhixiang.platform.identity.Actor;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
/** 学生只能查本人当前会员，管理员发放与撤销均有授权、幂等和审计。 */
@RestController
@RequestMapping("/api/v1")
public class MemberController {
 private final MemberService service;private final IdempotentWrites writes;
 /** 初始化模块组件，所需端口和配置由构造器注入。
 * @param service 会员用例
  * @param writes 同事务幂等 */
 public MemberController(MemberService service,IdempotentWrites writes){this.service=service;this.writes=writes;}
 /** 返回会员页。
 * @param id 学生
  * @param page 页码
  * @param size 每页数
  * @param q 套餐名称搜索
  * @param status SCHEDULED/ACTIVE/EXPIRED/REVOKED
  * @param sort 时间排序
  * @return 会员页 */
 @GetMapping("/admin/users/{id}/memberships") @PreAuthorize("hasAuthority('get_admin_users_id_memberships')")
 public PageResult<MembershipDtos.Member> list(@PathVariable UUID id,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size,@RequestParam(required=false) String q,@RequestParam(required=false) String status,@RequestParam(required=false) String sort){return service.list(id,new PageQuery(page,size,q,null,sort),status);}
 /** 返回201与会员版本。
 * @param id 学生
  * @param w 授予理由与期限
  * @param key 幂等键
  * @param actor 管理员
  * @return 201与会员版本 */
 @PostMapping("/admin/users/{id}/memberships") @PreAuthorize("hasAuthority('post_admin_users_id_memberships')")
 public ResponseEntity<String> grant(@PathVariable UUID id,@Valid @RequestBody MembershipDtos.Grant w,@RequestHeader("Idempotency-Key") String key,@AuthenticationPrincipal Actor actor){return writes.execute(actor.id(),"POST","/api/v1/admin/users/"+id+"/memberships",key,w,()->{var v=service.grant(id,w,actor.id());return ResponseEntity.status(201).eTag(Versions.etag(v.version())).body(v);});}
 /** 返回已撤销会员。
 * @param id 会员
  * @param w 撤销理由
  * @param match CAS
  * @param key 幂等键
  * @param actor 管理员
  * @return 已撤销会员 */
 @PostMapping("/admin/memberships/{id}/revoke") @PreAuthorize("hasAuthority('post_admin_memberships_id_revoke')")
 public ResponseEntity<String> revoke(@PathVariable UUID id,@Valid @RequestBody MembershipDtos.Reason w,@RequestHeader("If-Match") String match,@RequestHeader("Idempotency-Key") String key,@AuthenticationPrincipal Actor actor){long version=Versions.parse(match);return writes.execute(actor.id(),"POST","/api/v1/admin/memberships/"+id+"/revoke",key,List.of(version,w),()->{var v=service.revoke(id,version,w.reason(),actor.id());return ResponseEntity.ok().eTag(Versions.etag(v.version())).body(v);});}
 /** 返回本人有效会员或404。
 * @param actor 当前学生；不接受客户端userId
  * @return 本人有效会员或404 */
 @GetMapping("/student/membership") @PreAuthorize("hasAuthority('get_student_membership')")
 public ResponseEntity<MembershipDtos.Member> current(@AuthenticationPrincipal Actor actor){var v=service.current(actor.id());return ResponseEntity.ok().eTag(Versions.etag(v.version())).body(v);}
}
