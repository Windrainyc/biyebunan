package cn.zhixiang.platform.project;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import cn.zhixiang.platform.identity.Actor;
import cn.zhixiang.platform.shared.*;
/** 学生项目里程碑，只返回本人已绑定订单的项目。 */
@RestController
@RequestMapping("/api/v1/student/projects")
public class StudentMilestoneController {
 private final MilestoneService service;
 /** 构造接口。
 * @param service 用例 */
 public StudentMilestoneController(MilestoneService service){this.service=service;}
 /** 查询项目里程碑。
 * @param id 项目
 * @param page 页码
 * @param size 页大小
 * @param q 名称
 * @param sort 排序
 * @param actor 身份
 * @return 分页 */
 @PreAuthorize("hasAuthority('get_student_projects_id_milestones')")
 @GetMapping("/{id}/milestones")
 public PageResult<MilestoneDtos.View> list(@PathVariable UUID id,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size,@RequestParam(required=false) String q,@RequestParam(required=false) String sort,@AuthenticationPrincipal Actor actor){return service.list(id,new PageQuery(page,size,q,null,sort),actor);}
}
