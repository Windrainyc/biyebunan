package cn.zhixiang.platform.billing;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import cn.zhixiang.platform.identity.Actor;
/** 学生本人额度入口，不能传userId查看其他人。 */
@RestController
public class QuotaController {
 private final QuotaService service;
 /** 创建入口。
 * @param service 额度用例 */
 public QuotaController(QuotaService service){this.service=service;}
 /** 查询当前可用及冻结额度。
 * @param actor 当前学生
 * @return 真实数据库额度 */
 @GetMapping("/api/v1/student/quota") @PreAuthorize("hasAuthority('get_student_quota')")
 public QuotaService.View current(@AuthenticationPrincipal Actor actor){return service.current(actor.id());}
}
