package cn.zhixiang.platform.navigation;
import cn.zhixiang.platform.shared.*;
import cn.zhixiang.platform.identity.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
/** 菜单树规则，树互斥锁将祖先验证和写入串行化，防止两个并发移动形成环。 */
@Service
public class MenuService {
 private final MenuMapper menus;private final RoleMapper roles;private final WriteMapper writes;
 /** 初始化模块组件，所需端口和配置由构造器注入。
 * @param menus 树存储
  * @param roles 权限平台目录
  * @param writes 审计事件 */
 public MenuService(MenuMapper menus,RoleMapper roles,WriteMapper writes){this.menus=menus;this.roles=roles;this.writes=writes;}
 /** 返回管理页。
 * @param q 分页
  * @param platform 可选平台
  * @return 管理页 */
 @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
 public PageResult<MenuDtos.View> list(MenuDtos.Query q,String platform){if(platform!=null&&!Set.of("admin","student","partner").contains(platform))throw new BusinessException(400,"INVALID_FILTER","平台无效");return new PageResult<>(menus.list(q,platform).stream().map(this::view).toList(),q.page(),q.size(),menus.count(q,platform));}
 /** 返回授权菜单。
 * @param q 分页
  * @param actor 当前角色，不接受请求体指定平台
  * @return 授权菜单 */
 @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
 public PageResult<MenuDtos.View> visible(MenuDtos.Query q,Actor actor){return new PageResult<>(menus.visible(q,actor.platform(),actor.id()).stream().map(this::view).toList(),q.page(),q.size(),menus.visibleCount(q,actor.platform(),actor.id()));}
 /** 返回元数据。
 * @param id 菜单
  * @return 元数据
  * @throws BusinessException 不存在 */
 public MenuDtos.View get(UUID id){var row=menus.get(id);if(row==null)throw missing();return view(row);}
 /** 返回新菜单。
 * @param w 输入
  * @param actor 操作者
  * @return 新菜单 */
 @Transactional(rollbackFor=Exception.class)
 public MenuDtos.View create(MenuDtos.Write w,UUID actor){menus.lockTree();UUID id=UUID.randomUUID();validate(id,w);menus.insert(id,w,actor);audit(id,actor,"CREATED");return get(id);}
 /** 返回新版本。
 * @param id 菜单
  * @param version CAS
  * @param w 新配置
  * @param actor 操作者
  * @return 新版本
  * @throws BusinessException 平台变更、环、非法父项或停用有启用子项的节点 */
 @Transactional(rollbackFor=Exception.class)
 public MenuDtos.View update(UUID id,long version,MenuDtos.Write w,UUID actor){menus.lockTree();var old=checked(id,version);if(!old.platform().equals(w.platform()))throw new BusinessException(422,"INVALID_TRANSITION","菜单平台不能变更");validate(id,w);if(w.status().equals("DISABLED")&&menus.enabledChildren(id)>0)throw new BusinessException(409,"RESOURCE_REFERENCED","请先停用子菜单");if(menus.update(id,version,w,actor)!=1)throw conflict();audit(id,actor,"UPDATED");return get(id);}
 /** 返回变更结果。
 * @param id 菜单
  * @param version CAS
  * @param state 目标状态
  * @param actor 操作者
  * @return 变更结果 */
 @Transactional(rollbackFor=Exception.class)
 public MenuDtos.View status(UUID id,long version,String state,UUID actor){menus.lockTree();var r=checked(id,version);if(r.status().equals(state))return view(r);return update(id,version,new MenuDtos.Write(r.name(),r.code(),r.platform(),r.parentId(),r.path(),r.permissionCode(),r.sortOrder(),state),actor);}
 /** 删除未被引用的资源，调用方必须先完成权限与版本检查。
 * @param id 菜单
  * @param version CAS
  * @param actor 操作者；有子项拒绝删除 */
 @Transactional(rollbackFor=Exception.class)
 public void delete(UUID id,long version,UUID actor){menus.lockTree();checked(id,version);menus.delete(id);audit(id,actor,"DELETED");}
 private void validate(UUID id,MenuDtos.Write w){
  if(!roles.allowed(w.platform()).contains(w.permissionCode()))throw new BusinessException(422,"INVALID_REFERENCE","权限不属于菜单平台");
  Set<UUID> visited=new HashSet<>();visited.add(id);UUID parent=w.parentId();
  while(parent!=null){if(!visited.add(parent))throw new BusinessException(422,"MENU_CYCLE","父级不能指向自身或后代");var row=menus.get(parent);if(row==null||!row.platform().equals(w.platform()))throw new BusinessException(422,"INVALID_REFERENCE","父菜单必须存在且属于同一平台");if(w.status().equals("ENABLED")&&!row.status().equals("ENABLED"))throw new BusinessException(422,"INVALID_REFERENCE","启用菜单的祖先必须启用");parent=row.parentId();}
 }
 private MenuMapper.Row checked(UUID id,long version){var r=menus.get(id);if(r==null)throw missing();if(r.version()!=version)throw conflict();return r;}
 private MenuDtos.View view(MenuMapper.Row r){return new MenuDtos.View(r.id(),r.version(),r.createdAt(),r.updatedAt(),r.name(),r.code(),r.platform(),r.parentId(),r.path(),r.permissionCode(),r.sortOrder(),r.status());}
 private void audit(UUID id,UUID actor,String action){writes.audit(UUID.randomUUID(),actor,action,"menu",id);writes.event(UUID.randomUUID(),id,"menu."+action,"{\"id\":\""+id+"\"}");}
 private static BusinessException missing(){return new BusinessException(404,"RESOURCE_NOT_FOUND","菜单不存在");}
 private static BusinessException conflict(){return new BusinessException(412,"VERSION_CONFLICT","菜单版本已改变");}
}
