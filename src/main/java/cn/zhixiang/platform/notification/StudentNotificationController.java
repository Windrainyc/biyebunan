package cn.zhixiang.platform.notification;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import cn.zhixiang.platform.identity.Actor;
import cn.zhixiang.platform.shared.*;
/** student端本人通知，平台校验由认证过滤器执行。 */
@RestController @RequestMapping("/api/v1/student/notifications")
public class StudentNotificationController {
 private final NotificationService service;private final IdempotentWrites writes;
 /** 创建接口。
  * @param service 本人收件箱
  * @param writes 幂等事务 */
 public StudentNotificationController(NotificationService service,IdempotentWrites writes){this.service=service;this.writes=writes;}
 /** 查询本人通知。
  * @param page 页码
  * @param size 页大小
  * @param q 安全文案搜索
  * @param status READ或UNREAD
  * @param sort 白名单排序
  * @param actor 当前用户
  * @return 分页 */
 @GetMapping @PreAuthorize("hasAuthority('get_student_notifications')")
 public PageResult<NotificationService.View> list(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size,@RequestParam(required=false) String q,@RequestParam(required=false) String status,@RequestParam(required=false) String sort,@AuthenticationPrincipal Actor actor){return service.list(page,size,q,status,sort,actor);}
 /** 幂等标记本人通知已读。
  * @param id 通知ID
  * @param key 幂等键
  * @param actor 当前身份
  * @return 204；非本人404 */
 @PostMapping("/{id}/read") @PreAuthorize("hasAuthority('post_student_notifications_id_read')")
 public ResponseEntity<String> read(@PathVariable UUID id,@RequestHeader("Idempotency-Key") String key,@AuthenticationPrincipal Actor actor){return writes.execute(actor.id(),"POST","/api/v1/student/notifications/"+id+"/read",key,id,()->{service.read(id,actor);return ResponseEntity.noContent().build();});}
}
