package cn.zhixiang.platform.knowledge;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import cn.zhixiang.platform.shared.*;
/** 标签用例服务；锁定记录后执行版本检查，变更、审计和 outbox 同事务。 */
@Service
public class TagService {
 private final CategoryMapper tree;private final TagMapper mapper; private final WriteMapper writes;
 
 /** 初始化模块组件，所需端口和配置由构造器注入。
 * @param tree 分类与标签引用写锁
 * @param mapper 数据端口
 * @param writes 审计事件端口  */
 public TagService(CategoryMapper tree,TagMapper mapper,WriteMapper writes) {this.tree=tree;this.mapper=mapper;this.writes=writes;}
 /** 返回过滤后的分页结果。
 * @param q 查询条件
 * @return 过滤后的分页结果 */
 @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
 public PageResult<TagDtos.View> list(PageQuery q) {return new PageResult<>(mapper.list(q).stream().map(this::view).toList(),q.page(),q.size(),mapper.count(q));}
 /** 返回对外 DTO。
 * @param id 标识
 * @return 对外 DTO
 * @throws BusinessException 不存在 */
 public TagDtos.View get(UUID id) {var v=mapper.get(id);if(v==null) throw new BusinessException(404,"RESOURCE_NOT_FOUND","资源不存在");return view(v);}
 /** 返回新资源。
 * @param w 写入数据
 * @param actor 操作者
 * @return 新资源 */
 @Transactional(rollbackFor=Exception.class)
 public TagDtos.View create(TagDtos.Write w,UUID actor) {tree.lockTree();UUID id=UUID.randomUUID();mapper.insert(id,w,actor);audit(id,actor,"CREATED");return get(id);}
 /** 返回新版本。
 * @param id 标识
 * @param version 期望版本
 * @param w 新数据
 * @param actor 操作者
 * @return 新版本 */
 @Transactional(rollbackFor=Exception.class)
 public TagDtos.View update(UUID id,long version,TagDtos.Write w,UUID actor) {tree.lockTree();checked(id,version);if(mapper.update(id,version,w,actor)!=1) conflict();audit(id,actor,"UPDATED");return get(id);}
 /** 返回新版本或同值原版本。
 * @param id 标识
 * @param version 期望版本
 * @param status 目标状态
 * @param actor 操作者
 * @return 新版本或同值原版本 */
 @Transactional(rollbackFor=Exception.class)
 public TagDtos.View status(UUID id,long version,TagDtos.Status status,UUID actor) {tree.lockTree();var old=checked(id,version);if(old.status()==status)return view(old); if(mapper.status(id,version,status,actor)!=1) conflict();audit(id,actor,"STATUS_CHANGED");return get(id);}
 /** 删除未被引用的资源，调用方必须先完成权限与版本检查。
 * @param id 标识
 * @param version 期望版本
 * @param actor 操作者；有引用时数据库拒绝并回滚审计 */
 @Transactional(rollbackFor=Exception.class)
 public void delete(UUID id,long version,UUID actor) {tree.lockTree();checked(id,version);if(mapper.delete(id,version)!=1) conflict();audit(id,actor,"DELETED");}
 private TagMapper.Row checked(UUID id,long version) {var old=mapper.lock(id);if(old==null)throw new BusinessException(404,"RESOURCE_NOT_FOUND","资源不存在");if(old.version()!=version)conflict();return old;}
 private void conflict() {throw new BusinessException(412,"VERSION_CONFLICT","数据已被修改，请刷新后重试");}
 private void audit(UUID id,UUID actor,String action) {writes.audit(UUID.randomUUID(),actor,action,"knowledge_tag",id);writes.event(UUID.randomUUID(),id,"knowledge_tag."+action,"{\"id\":\""+id+"\"}");}
  private TagDtos.View view(TagMapper.Row r){return new TagDtos.View(r.id(),r.version(),r.createdAt(),r.updatedAt(),r.name(),r.code(),r.status());}

}
