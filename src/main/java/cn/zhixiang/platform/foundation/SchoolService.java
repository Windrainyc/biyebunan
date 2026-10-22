package cn.zhixiang.platform.foundation;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import cn.zhixiang.platform.shared.*;
/** School 用例服务；锁定记录后执行版本检查，变更、审计和 outbox 同事务。 */
@Service
public class SchoolService {
 private final SchoolMapper mapper; private final WriteMapper writes;
 
 /** @param mapper 数据端口 @param writes 审计事件端口  */
 public SchoolService(SchoolMapper mapper,WriteMapper writes) {this.mapper=mapper;this.writes=writes;}
 /** @param q 查询条件 @return 过滤后的分页结果 */
 @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
 public PageResult<SchoolDtos.View> list(PageQuery q) {return new PageResult<>(mapper.list(q),q.page(),q.size(),mapper.count(q));}
 /** @param id 标识 @return 对外 DTO @throws BusinessException 不存在 */
 public SchoolDtos.View get(UUID id) {var v=mapper.get(id);if(v==null) throw new BusinessException(404,"RESOURCE_NOT_FOUND","资源不存在");return v;}
 /** @param w 写入数据 @param actor 操作者 @return 新资源 */
 @Transactional(rollbackFor=Exception.class)
 public SchoolDtos.View create(SchoolDtos.Write w,UUID actor) {  UUID id=UUID.randomUUID();mapper.insert(id,w,actor);audit(id,actor,"CREATED");return get(id);}
 /** @param id 标识 @param version 期望版本 @param w 新数据 @param actor 操作者 @return 新版本 */
 @Transactional(rollbackFor=Exception.class)
 public SchoolDtos.View update(UUID id,long version,SchoolDtos.Write w,UUID actor) {  checked(id,version);if(mapper.update(id,version,w,actor)!=1) conflict();audit(id,actor,"UPDATED");return get(id);}
 /** @param id 标识 @param version 期望版本 @param status 目标状态 @param actor 操作者 @return 新版本或同值原版本 */
 @Transactional(rollbackFor=Exception.class)
 public SchoolDtos.View status(UUID id,long version,SchoolDtos.Status status,UUID actor) {var old=checked(id,version);if(old.status()==status)return old; if(mapper.status(id,version,status,actor)!=1) conflict();audit(id,actor,"STATUS_CHANGED");return get(id);}
 /** @param id 标识 @param version 期望版本 @param actor 操作者；有引用时数据库拒绝并回滚审计 */
 @Transactional(rollbackFor=Exception.class)
 public void delete(UUID id,long version,UUID actor) {checked(id,version);if(mapper.delete(id,version)!=1) conflict();audit(id,actor,"DELETED");}
 private SchoolDtos.View checked(UUID id,long version) {var old=mapper.lock(id);if(old==null)throw new BusinessException(404,"RESOURCE_NOT_FOUND","资源不存在");if(old.version()!=version)conflict();return old;}
 private void conflict() {throw new BusinessException(412,"VERSION_CONFLICT","数据已被修改，请刷新后重试");}
 private void audit(UUID id,UUID actor,String action) {writes.audit(UUID.randomUUID(),actor,action,"school",id);writes.event(UUID.randomUUID(),id,"school."+action,"{\"id\":\""+id+"\"}");}
 
}
