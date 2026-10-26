package cn.zhixiang.platform.identity;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import cn.zhixiang.platform.shared.*;
/** partner端本人资料入口，角色固定在服务端。 */
@RestController @RequestMapping("/api/v1/partner/me")
public class PartnerProfileController {
 private final ProfileService service;private final IdempotentWrites writes;
 /** 创建入口。
  * @param service 资料用例
  * @param writes 幂等事务 */
 public PartnerProfileController(ProfileService service,IdempotentWrites writes){this.service=service;this.writes=writes;}
 /** 获取本人资料和ETag。
  * @param actor 当前身份
  * @return 资料 */
 @GetMapping @PreAuthorize("hasAuthority('get_partner_me')")
 public ResponseEntity<AuthDtos.User> get(@AuthenticationPrincipal Actor actor){var result=service.get(actor,"partner");return ResponseEntity.ok().eTag(Versions.etag(result.version())).body(result);}
 /** 修改本人资料。
  * @param w 昵称与教育资料
  * @param match 预期ETag
  * @param key 幂等键
  * @param actor 当前身份
  * @return 新资料和ETag */
 @PutMapping @PreAuthorize("hasAuthority('put_partner_me')")
 public ResponseEntity<String> update(@Valid @RequestBody ProfileService.Write w,@RequestHeader("If-Match") String match,@RequestHeader("Idempotency-Key") String key,@AuthenticationPrincipal Actor actor){long version=Versions.parse(match);return writes.execute(actor.id(),"PUT","/api/v1/partner/me",key,Map.of("version",version,"body",w),()->{var result=service.update(actor,"partner",version,w);return ResponseEntity.ok().eTag(Versions.etag(result.version())).body(result);});}
}
