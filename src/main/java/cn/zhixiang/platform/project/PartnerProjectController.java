package cn.zhixiang.platform.project;
import java.util.*;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import cn.zhixiang.platform.identity.Actor;
import cn.zhixiang.platform.shared.*;
/** partner 平台项目入口，数据范围取自服务端认证结果。 */
@RestController
@RequestMapping("/api/v1/partner/projects")
public class PartnerProjectController {
 private final ProjectService service;private final IdempotentWrites writes;
 /** 初始化接口组件。
 * @param service 项目用例
 * @param writes 幂等写协调器 */
 public PartnerProjectController(ProjectService service,IdempotentWrites writes){this.service=service;this.writes=writes;}
 /** 分页读取可见项目。
 * @param page 页码
 * @param size 页大小
 * @param q 搜索词
 * @param status 状态
 * @param sort 排序
 * @param actor 权威身份
 * @return 范围内分页 */
 @PreAuthorize("hasAuthority('get_partner_projects')")
 @GetMapping
 public PageResult<ProjectDtos.Progress> list(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size,@RequestParam(required=false) String q,@RequestParam(required=false) String status,@RequestParam(required=false) String sort,@AuthenticationPrincipal Actor actor){return service.partnerList(new ProjectDtos.Query(page,size,q,status,sort),actor);}
 /** 获取可见项目。
 * @param id 项目ID
 * @param actor 权威身份
 * @return 不含需求或文件地址的进度摘要 */
 @PreAuthorize("hasAuthority('get_partner_projects_id_progress')")
 @GetMapping("/{id}/progress")
 public ResponseEntity<ProjectDtos.Progress> get(@PathVariable UUID id,@AuthenticationPrincipal Actor actor){var v=service.partnerProgress(id,actor);return ResponseEntity.ok().body(v);}
}
