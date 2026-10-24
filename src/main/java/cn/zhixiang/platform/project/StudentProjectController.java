package cn.zhixiang.platform.project;
import java.util.*;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import cn.zhixiang.platform.identity.Actor;
import cn.zhixiang.platform.shared.*;
/** student 平台项目入口，数据范围取自服务端认证结果。 */
@RestController
@RequestMapping("/api/v1/student/projects")
public class StudentProjectController {
 private final ProjectService service;private final IdempotentWrites writes;
 /** 初始化接口组件。
 * @param service 项目用例
 * @param writes 幂等写协调器 */
 public StudentProjectController(ProjectService service,IdempotentWrites writes){this.service=service;this.writes=writes;}
 /** 分页读取可见项目。
 * @param page 页码
 * @param size 页大小
 * @param q 搜索词
 * @param status 状态
 * @param sort 排序
 * @param actor 权威身份
 * @return 范围内分页 */
 @PreAuthorize("hasAuthority('get_student_projects')")
 @GetMapping
 public PageResult<ProjectDtos.View> list(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size,@RequestParam(required=false) String q,@RequestParam(required=false) String status,@RequestParam(required=false) String sort,@AuthenticationPrincipal Actor actor){return service.list(new ProjectDtos.Query(page,size,q,status,sort),actor);}
 /** 获取可见项目。
 * @param id 项目ID
 * @param actor 权威身份
 * @return 响应和版本 */
 @PreAuthorize("hasAuthority('get_student_projects_id')")
 @GetMapping("/{id}")
 public ResponseEntity<ProjectDtos.View> get(@PathVariable UUID id,@AuthenticationPrincipal Actor actor){var v=service.get(id,actor);return ResponseEntity.ok().eTag(Versions.etag(v.version())).body(v);}
}
