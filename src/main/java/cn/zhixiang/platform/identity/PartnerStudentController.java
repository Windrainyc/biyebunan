package cn.zhixiang.platform.identity;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import cn.zhixiang.platform.shared.*;
/** 中介学生入口，不接受partnerId查询参数。 */
@RestController @RequestMapping("/api/v1/partner/students")
public class PartnerStudentController {
 private final PartnerStudentService service;
 /** 创建入口。
  * @param service 查询用例 */
 public PartnerStudentController(PartnerStudentService service){this.service=service;}
 /** 查询名下学生。
  * @param page 页码
  * @param size 页大小
  * @param q 昵称或脱敏手机号搜索
  * @param status 学生启停筛选
  * @param sort 白名单排序
  * @param actor 中介身份
  * @return 分页 */
 @GetMapping @PreAuthorize("hasAuthority('get_partner_students')")
 public PageResult<PartnerStudentService.View> list(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size,@RequestParam(required=false) String q,@RequestParam(required=false) String status,@RequestParam(required=false) String sort,@AuthenticationPrincipal Actor actor){return service.list(new PageQuery(page,size,q,status,sort),actor);}
 /** 获取当前范围内学生摘要。
  * @param id 学生
  * @param actor 当前中介
  * @return 摘要 */
 @GetMapping("/{id}") @PreAuthorize("hasAuthority('get_partner_students_id')")
 public PartnerStudentService.View get(@PathVariable UUID id,@AuthenticationPrincipal Actor actor){return service.get(id,actor);}
}
