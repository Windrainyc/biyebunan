package cn.zhixiang.platform.forum;
import java.util.*;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import cn.zhixiang.platform.identity.Actor;
import cn.zhixiang.platform.shared.*;
/** admin平台帖子入口，服务端身份控制可见范围。 */
@RestController @RequestMapping("/api/v1/admin/posts")
public class AdminPostController {
 private final PostService service;private final IdempotentWrites writes;
 /** 构造入口。
  * @param service 帖子用例
  * @param writes 幂等协调 */
 public AdminPostController(PostService service,IdempotentWrites writes){this.service=service;this.writes=writes;}
 /** 查询当前权限内的帖子。
  * @param page 页码
  * @param size 页大小
  * @param q 标题正文搜索
  * @param status 帖子审核状态
  * @param sort 白名单时间排序
  * @param boardId 板块筛选
  * @param actor 身份
  * @return 分页 */
 @GetMapping @PreAuthorize("hasAuthority('get_admin_posts')")
 public PageResult<PostDtos.View> list(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size,@RequestParam(required=false) String q,@RequestParam(required=false) String status,@RequestParam(required=false) String sort,@RequestParam(required=false) UUID boardId,@AuthenticationPrincipal Actor actor){return service.list(new PageQuery(page,size,q,null,sort),status,boardId,actor);}
 /** 读取可见详情及CAS版本。
  * @param id 帖子
  * @param actor 身份
  * @return 详情 */
 @GetMapping("/{id}") @PreAuthorize("hasAuthority('get_admin_posts_id')")
 public ResponseEntity<PostDtos.View> get(@PathVariable UUID id,@AuthenticationPrincipal Actor actor){var v=service.get(id,actor);return ResponseEntity.ok().eTag(Versions.etag(v.version())).body(v);}
 /** 审核、隐藏或锁帖。
  * @param id 帖子
  * @param w 动作及理由
  * @param match CAS
  * @param key 幂等键
  * @param actor 管理员
  * @return 新版本 */
 @PostMapping("/{id}/moderation") @PreAuthorize("hasAuthority('post_admin_posts_id_moderation')")
 public ResponseEntity<String> moderate(@PathVariable UUID id,@Valid @RequestBody PostDtos.Moderation w,@RequestHeader("If-Match") String match,@RequestHeader("Idempotency-Key") String key,@AuthenticationPrincipal Actor actor){long version=Versions.parse(match);return writes.execute(actor.id(),"POST","/api/v1/admin/posts/"+id+"/moderation",key,List.of(version,w),()->{var v=service.moderate(id,version,w,actor);return ResponseEntity.ok().eTag(Versions.etag(v.version())).body(v);});}
}
