package cn.zhixiang.platform.operations;
import java.time.LocalDate;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import cn.zhixiang.platform.identity.Actor;
/** admin平台统计入口；范围由服务端身份确定。 */
@RestController @RequestMapping("/api/v1/admin/statistics")
public class AdminStatisticsController {
 private final StatisticsService service;
 /** 注入聚合用例。
  * @param service 查询服务 */
 public AdminStatisticsController(StatisticsService service){this.service=service;}
 /** 查询日期区间的统计。
  * @param from 包含的首日
  * @param to 包含的末日
  * @param actor 当前身份
  * @return 不缓存的日统计 */
 @GetMapping @PreAuthorize("hasAuthority('get_admin_statistics')")
 public ResponseEntity<StatisticsService.View> get(@RequestParam LocalDate from,@RequestParam LocalDate to,@AuthenticationPrincipal Actor actor){return ResponseEntity.ok().header("Cache-Control","no-store").body(service.get(from,to,actor));}
}
