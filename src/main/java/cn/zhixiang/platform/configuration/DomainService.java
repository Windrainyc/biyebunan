package cn.zhixiang.platform.configuration;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import cn.zhixiang.platform.shared.*;
/** 配置用例服务；锁定记录后执行版本检查，变更、审计和 outbox 同事务。 */
@Service
public class DomainService {
 private final DomainMapper mapper; private final WriteMapper writes;
 
 /** 初始化模块组件，所需端口和配置由构造器注入。
 * @param mapper 数据端口
 * @param writes 审计事件端口  */
 public DomainService(DomainMapper mapper,WriteMapper writes) {this.mapper=mapper;this.writes=writes;}
 /** 返回过滤后的分页结果。
 * @param q 查询条件
 * @return 过滤后的分页结果 */
 @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
 public PageResult<DomainDtos.View> list(PageQuery q) {return new PageResult<>(mapper.list(q).stream().map(this::view).toList(),q.page(),q.size(),mapper.count(q));}
 /** 返回对外 DTO。
 * @param id 标识
 * @return 对外 DTO
 * @throws BusinessException 不存在 */
 public DomainDtos.View get(UUID id) {var v=mapper.get(id);if(v==null) throw new BusinessException(404,"RESOURCE_NOT_FOUND","资源不存在");return view(v);}
 /** 返回新资源。
 * @param w 写入数据
 * @param actor 操作者
 * @return 新资源 */
 @Transactional(rollbackFor=Exception.class)
 public DomainDtos.View create(DomainDtos.Write w,UUID actor) {w=normalize(w);UUID id=UUID.randomUUID();mapper.insert(id,w,actor);audit(id,actor,"CREATED");return get(id);}
 /** 返回新版本。
 * @param id 标识
 * @param version 期望版本
 * @param w 新数据
 * @param actor 操作者
 * @return 新版本 */
 @Transactional(rollbackFor=Exception.class)
 public DomainDtos.View update(UUID id,long version,DomainDtos.Write w,UUID actor) {w=normalize(w);var old=checked(id,version);if(old.status()==DomainDtos.Status.ENABLED && (!old.hostname().equals(w.hostname()) || old.platform()!=w.platform() || !old.certificateRef().equals(w.certificateRef())))throw new BusinessException(409,"CONFIG_MUST_BE_DISABLED","更换域名、平台或证书引用前请先停用配置");if(mapper.update(id,version,w,actor)!=1) conflict();audit(id,actor,"UPDATED");return get(id);}
 /** 返回新版本或同值原版本。
 * @param id 标识
 * @param version 期望版本
 * @param status 目标状态
 * @param actor 操作者
 * @return 新版本或同值原版本 */
 @Transactional(rollbackFor=Exception.class)
 public DomainDtos.View status(UUID id,long version,DomainDtos.Status status,UUID actor) {var old=checked(id,version);if(old.status()==status)return view(old); if(mapper.status(id,version,status,actor)!=1) conflict();audit(id,actor,"STATUS_CHANGED");return get(id);}
 /** 删除未被引用的资源，调用方必须先完成权限与版本检查。
 * @param id 标识
 * @param version 期望版本
 * @param actor 操作者；有引用时数据库拒绝并回滚审计 */
 @Transactional(rollbackFor=Exception.class)
 public void delete(UUID id,long version,UUID actor) {var old=checked(id,version);if(old.status()!=DomainDtos.Status.DISABLED)throw new BusinessException(409,"CONFIG_MUST_BE_DISABLED","请先停用配置再删除");if(mapper.delete(id,version)!=1) conflict();audit(id,actor,"DELETED");}
 private DomainMapper.Row checked(UUID id,long version) {var old=mapper.lock(id);if(old==null)throw new BusinessException(404,"RESOURCE_NOT_FOUND","资源不存在");if(old.version()!=version)conflict();return old;}
 private void conflict() {throw new BusinessException(412,"VERSION_CONFLICT","数据已被修改，请刷新后重试");}
 private void audit(UUID id,UUID actor,String action) {writes.audit(UUID.randomUUID(),actor,action,"domain_config",id);writes.event(UUID.randomUUID(),id,"domain_config."+action,"{\"id\":\""+id+"\"}");}
  private DomainDtos.View view(DomainMapper.Row r){return new DomainDtos.View(r.id(),r.version(),r.createdAt(),r.updatedAt(),r.name(),r.hostname(),r.platform(),r.certificateRef(),r.status());}

 private DomainDtos.Write normalize(DomainDtos.Write w) {
  String host=w.hostname().toLowerCase(java.util.Locale.ROOT);
  if(!host.matches("(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}"))throw new BusinessException(422,"DOMAIN_INVALID","域名标签格式不合法");
  return new DomainDtos.Write(w.name(),host,w.platform(),w.certificateRef(),w.status());
 }
}
