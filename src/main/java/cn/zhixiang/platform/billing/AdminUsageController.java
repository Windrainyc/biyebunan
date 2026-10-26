package cn.zhixiang.platform.billing;
import java.time.LocalDate;
import org.springframework.web.bind.annotation.*;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import cn.zhixiang.platform.identity.Actor;
import cn.zhixiang.platform.shared.PageResult;
/** 管理员账务入口，只返回用量，不包含学生私聊内容。 */
@RestController
public class AdminUsageController {
 private final UsageService service;
 /** 创建入口。
  * @param service 用量用例 */
 public AdminUsageController(UsageService service){this.service=service;}
 /** 分页查看账务用量。
  * @param page 页码
  * @param size 每页数
  * @param q 运行ID字面搜索
  * @param status 账务状态
  * @param sort 排序
  * @param from UTC起始日期，包含
  * @param to UTC结束日期，包含
  * @param studentId 可选学生筛选
  * @param actor 管理员身份
  * @return 账单分页 */
 @GetMapping("/api/v1/admin/usage") @PreAuthorize("hasAuthority('billing:read')")
 public PageResult<UsageService.View> list(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size,@RequestParam(required=false) String q,@RequestParam(required=false) String status,@RequestParam(required=false) String sort,@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to,@RequestParam(required=false) java.util.UUID studentId,@AuthenticationPrincipal Actor actor){return service.adminList(new UsageQuery(page,size,q,status,sort,from,to),studentId,actor);}
}
