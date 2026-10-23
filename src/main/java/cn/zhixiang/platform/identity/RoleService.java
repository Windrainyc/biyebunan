package cn.zhixiang.platform.identity;
import cn.zhixiang.platform.shared.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
/** 角色管理用例；禁止提权、跨平台授权和修改内置角色。 */
@Service
public class RoleService {
 private final RoleMapper roles;private final IdentityMapper identity;private final WriteMapper writes;
 /** @param roles 角色端口
  * @param identity 操作者当前权限
  * @param writes 审计事件端口 */
 public RoleService(RoleMapper roles,IdentityMapper identity,WriteMapper writes){this.roles=roles;this.identity=identity;this.writes=writes;}
 /** @param q 分页条件
  * @return 角色分页 */
 @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
 public PageResult<RoleDtos.View> list(PageQuery q){return new PageResult<>(roles.list(q).stream().map(this::view).toList(),q.page(),q.size(),roles.count(q));}
 /** @param id 标识
  * @return 角色详情 */
 public RoleDtos.View get(UUID id){var r=roles.get(id);if(r==null)throw missing();return view(r);}
 /** @param q 分页搜索
  * @param actor 当前身份
  * @return 仅操作者可授予的权限目录 */
 public PageResult<RoleDtos.Permission> permissions(PageQuery q,UUID actor){
  var owned=new HashSet<>(identity.permissions(actor));boolean superAdmin=isSuper(actor);
  var values=roles.permissions().stream().filter(p->superAdmin||owned.contains(p.code())).filter(p->p.code().contains(q.q())||p.description().contains(q.q())).toList();
  if("DISABLED".equals(q.status()))values=List.of();
  int from=Math.min(q.offset(),values.size()),to=Math.min(from+q.size(),values.size());
  return new PageResult<>(values.subList(from,to),q.page(),q.size(),values.size());
 }
 /** @param w 新角色
  * @param actor 操作者
  * @return 创建结果 */
 @Transactional(rollbackFor=Exception.class)
 public RoleDtos.View create(RoleDtos.Write w,UUID actor){roles.lockIam();validate(w,actor);UUID id=UUID.randomUUID();roles.insert(id,w);setPermissions(id,w.permissionCodes());audit(id,actor,"CREATED");return get(id);}
 /** @param id 标识
  * @param version 期望版本
  * @param w 完整新值
  * @param actor 操作者
  * @return 更新角色 */
 @Transactional(rollbackFor=Exception.class)
 public RoleDtos.View update(UUID id,long version,RoleDtos.Write w,UUID actor){
  roles.lockIam();var old=mutable(id,version);checkManage(id,actor);validate(w,actor);
  if(!old.platform().equals(w.platform()))throw new BusinessException(422,"INVALID_TRANSITION","角色平台创建后不可更改");
  if(roles.update(id,version,w)!=1)throw conflict();setPermissions(id,w.permissionCodes());roles.invalidate(id);audit(id,actor,"UPDATED");return get(id);
 }
 /** @param id 标识
  * @param version 期望版本
  * @param status 目标状态
  * @param actor 操作者
  * @return 启停后角色 */
 @Transactional(rollbackFor=Exception.class)
 public RoleDtos.View status(UUID id,long version,String status,UUID actor){
  roles.lockIam();var old=mutable(id,version);checkManage(id,actor);if(old.status().equals(status))return view(old);
  return update(id,version,new RoleDtos.Write(old.name(),old.code(),old.platform(),old.dataScope(),roles.codes(id),status),actor);
 }
 /** @param id 标识
  * @param version 期望版本
  * @param actor 操作者；有用户引用则数据库拒绝删除 */
 @Transactional(rollbackFor=Exception.class)
 public void delete(UUID id,long version,UUID actor){roles.lockIam();mutable(id,version);checkManage(id,actor);if(roles.delete(id,version)!=1)throw conflict();audit(id,actor,"DELETED");}
 private void validate(RoleDtos.Write w,UUID actor){
  String scope=Map.of("admin","ALL","student","SELF","partner","OWNED").get(w.platform());
  if(!Objects.equals(scope,w.dataScope()))throw new BusinessException(422,"INVALID_DATA_SCOPE","角色范围与平台不匹配");
  Set<String> requested=new HashSet<>(w.permissionCodes());
  if(requested.size()!=w.permissionCodes().size())throw new BusinessException(400,"VALIDATION_ERROR","权限不能重复");
  if(!new HashSet<>(roles.allowed(w.platform())).containsAll(requested))throw new BusinessException(403,"PERMISSION_DENIED","不能授予跨平台或不存在的权限");
  // 学生/中介权限通过平台内置角色授予；自定义端角色仍需操作者自身具备对应委派范围。
  if(!isSuper(actor) && !new HashSet<>(identity.permissions(actor)).containsAll(requested))throw new BusinessException(403,"PERMISSION_DENIED","不能授予自身没有的权限");
 }
 private void checkManage(UUID id,UUID actor){if(!isSuper(actor)&&!new HashSet<>(identity.permissions(actor)).containsAll(roles.codes(id)))throw new BusinessException(403,"PERMISSION_DENIED","不能修改权限高于自身的角色");}
 private boolean isSuper(UUID actor){return identity.superAdmin(actor)>0;}
 private RoleMapper.Row mutable(UUID id,long version){var r=roles.lock(id);if(r==null)throw missing();if(r.builtIn())throw new BusinessException(403,"SYSTEM_RESOURCE","内置角色由系统迁移维护，不能删除或修改");if(r.version()!=version)throw conflict();return r;}
 private void setPermissions(UUID id,List<String> permissions){roles.clearPermissions(id);for(String p:permissions)roles.addPermission(id,p);}
 private RoleDtos.View view(RoleMapper.Row r){return new RoleDtos.View(r.id(),r.version(),r.createdAt(),r.updatedAt(),r.name(),r.code(),r.platform(),r.dataScope(),roles.codes(r.id()),r.status());}
 private void audit(UUID id,UUID actor,String action){writes.audit(UUID.randomUUID(),actor,action,"role",id);writes.event(UUID.randomUUID(),id,"role."+action,"{\"id\":\""+id+"\"}");}
 private static BusinessException missing(){return new BusinessException(404,"RESOURCE_NOT_FOUND","角色不存在");}
 private static BusinessException conflict(){return new BusinessException(412,"VERSION_CONFLICT","角色版本已改变");}
}
