package cn.zhixiang.platform.identity;
import cn.zhixiang.platform.shared.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
/** 返回操作者可授予的权限，不提供任意权限代码写入入口。 */
@RestController
public class PermissionController {
 private final RoleService roles;
 /** 初始化模块组件，所需端口和配置由构造器注入。
 * @param roles 权限目录用例 */
 public PermissionController(RoleService roles){this.roles=roles;}
 /** 返回可授予权限页。
 * @param page 页码
  * @param size 页大小
  * @param q 搜索代码或说明
  * @param status ENABLED或DISABLED
  * @param sort 兼容分页排序参数，目录内部按权限代码稳定排序
  * @param actor 当前身份
  * @return 可授予权限页 */
 @GetMapping("/api/v1/admin/permissions")
 @PreAuthorize("hasAuthority('get_admin_permissions')")
 public PageResult<RoleDtos.Permission> list(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size,@RequestParam(required=false) String q,@RequestParam(required=false) String status,@RequestParam(required=false) String sort,@AuthenticationPrincipal Actor actor){return roles.permissions(new PageQuery(page,size,q,status,sort),actor.id());}
}
