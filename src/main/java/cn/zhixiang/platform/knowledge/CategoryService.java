package cn.zhixiang.platform.knowledge;
import cn.zhixiang.platform.shared.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
/** 分类树用例，拒绝环和启用状态断链，不级联删除子节点。 */
@Service
public class CategoryService {
 private final CategoryMapper mapper;private final WriteMapper writes;
 /** 初始化模块组件，所需端口和配置由构造器注入。
 * @param mapper 分类端口
  * @param writes 审计与outbox */
 public CategoryService(CategoryMapper mapper,WriteMapper writes){this.mapper=mapper;this.writes=writes;}
 /** 返回同快照页。
 * @param q 分页
  * @return 同快照页 */
 @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
 public PageResult<CategoryDtos.View> list(PageQuery q){return new PageResult<>(mapper.list(q).stream().map(this::view).toList(),q.page(),q.size(),mapper.count(q));}
 /** 返回元数据。
 * @param id 分类
  * @return 元数据
  * @throws BusinessException 不存在 */
 public CategoryDtos.View get(UUID id){var r=mapper.get(id);if(r==null)throw missing();return view(r);}
 /** 返回分类。
 * @param w 新分类
  * @param actor 操作者
  * @return 分类 */
 @Transactional(rollbackFor=Exception.class)
 public CategoryDtos.View create(CategoryDtos.Write w,UUID actor){mapper.lockTree();UUID id=UUID.randomUUID();validate(id,w);mapper.insert(id,w,actor);audit(id,actor,"CREATED");return get(id);}
 /** 返回新版本。
 * @param id 分类
  * @param version CAS
  * @param w 新配置
  * @param actor 操作者
  * @return 新版本 */
 @Transactional(rollbackFor=Exception.class)
 public CategoryDtos.View update(UUID id,long version,CategoryDtos.Write w,UUID actor){mapper.lockTree();checked(id,version);validate(id,w);if(w.status().equals("DISABLED")&&mapper.enabledChildren(id)>0)throw new BusinessException(409,"RESOURCE_REFERENCED","请先停用子分类");if(mapper.update(id,version,w,actor)!=1)throw conflict();audit(id,actor,"UPDATED");return get(id);}
 /** 返回更新结果。
 * @param id 分类
  * @param version CAS
  * @param state 状态
  * @param actor 操作者
  * @return 更新结果 */
 @Transactional(rollbackFor=Exception.class)
 public CategoryDtos.View status(UUID id,long version,String state,UUID actor){mapper.lockTree();var r=checked(id,version);if(r.status().equals(state))return view(r);return update(id,version,new CategoryDtos.Write(r.name(),r.code(),r.parentId(),r.sortOrder(),state),actor);}
 /** 删除未被引用的资源，调用方必须先完成权限与版本检查。
 * @param id 分类
  * @param version CAS
  * @param actor 操作者；删除有引用项会回滚整个事务 */
 @Transactional(rollbackFor=Exception.class)
 public void delete(UUID id,long version,UUID actor){mapper.lockTree();checked(id,version);mapper.delete(id);audit(id,actor,"DELETED");}
 private void validate(UUID id,CategoryDtos.Write w){Set<UUID> seen=new HashSet<>();seen.add(id);UUID parent=w.parentId();while(parent!=null){if(!seen.add(parent))throw new BusinessException(422,"CATEGORY_CYCLE","分类不能形成环");var r=mapper.get(parent);if(r==null||w.status().equals("ENABLED")&&!r.status().equals("ENABLED"))throw new BusinessException(422,"INVALID_REFERENCE","父分类不存在或已停用");parent=r.parentId();}}
 private CategoryMapper.Row checked(UUID id,long version){var r=mapper.get(id);if(r==null)throw missing();if(r.version()!=version)throw conflict();return r;}
 private CategoryDtos.View view(CategoryMapper.Row r){return new CategoryDtos.View(r.id(),r.version(),r.createdAt(),r.updatedAt(),r.name(),r.code(),r.parentId(),r.sortOrder(),r.status());}
 private void audit(UUID id,UUID actor,String action){writes.audit(UUID.randomUUID(),actor,action,"knowledge_category",id);writes.event(UUID.randomUUID(),id,"knowledge_category."+action,"{\"id\":\""+id+"\"}");}
 private static BusinessException missing(){return new BusinessException(404,"RESOURCE_NOT_FOUND","分类不存在");}
 private static BusinessException conflict(){return new BusinessException(412,"VERSION_CONFLICT","分类已改变");}
}
