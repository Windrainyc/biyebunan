package cn.zhixiang.platform.membership;
import cn.zhixiang.platform.shared.*;
import java.util.UUID;
import java.math.BigDecimal;
import java.time.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
/** 套餐用例。已授予权益不可通过编辑覆盖，只能追加生效版本。 */
@Service
public class PlanService {
 private final PlanMapper plans;private final WriteMapper writes;
 /** 初始化模块组件，所需端口和配置由构造器注入。
 * @param plans 套餐端口
  * @param writes 同事务审计与outbox */
 public PlanService(PlanMapper plans,WriteMapper writes){this.plans=plans;this.writes=writes;}
 /** 返回套餐页，同一数据库快照。
 * @param q 查询条件
  * @return 套餐页，同一数据库快照 */
 @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
 public PageResult<MembershipDtos.Plan> list(PageQuery q){return new PageResult<>(plans.list(q).stream().map(this::view).toList(),q.page(),q.size(),plans.count(q));}
 /** 返回当前已生效权益与元数据。
 * @param id 套餐
  * @return 当前已生效权益与元数据
  * @throws BusinessException 不存在 */
 @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
 public MembershipDtos.Plan get(UUID id){var r=plans.get(id);if(r==null)throw missing();return view(r);}
 /** 返回新套餐与初始不可变权益。
 * @param w 配置
  * @param actor 操作者
  * @return 新套餐与初始不可变权益 */
 @Transactional(rollbackFor=Exception.class)
 public MembershipDtos.Plan create(MembershipDtos.PlanWrite w,UUID actor){UUID id=UUID.randomUUID();plans.insert(id,w,actor);plans.append(UUID.randomUUID(),id,w.durationDays(),w.monthlyTokenQuota(),new BigDecimal(w.monthlyAmountLimit()),null);audit(id,actor,"CREATED");return get(id);}
 /** 返回更新后套餐。
 * @param id 套餐
  * @param version CAS版本
  * @param w 新值
  * @param actor 操作者
  * @return 更新后套餐
  * @throws BusinessException 已授予套餐直接改权益、版本冲突 */
 @Transactional(rollbackFor=Exception.class)
 public MembershipDtos.Plan update(UUID id,long version,MembershipDtos.PlanWrite w,UUID actor){
  checked(id,version);var old=plans.current(id);boolean changed=old.durationDays()!=w.durationDays()||old.monthlyTokenQuota()!=w.monthlyTokenQuota()||old.monthlyAmountLimit().compareTo(new BigDecimal(w.monthlyAmountLimit()))!=0;
  if(changed&&plans.references(id)>0)throw new BusinessException(422,"IMMUTABLE_BENEFIT","已使用套餐需追加权益版本，不能直接改写");
  if(plans.update(id,version,w.name(),w.code(),w.status(),actor)!=1)throw conflict();
  if(changed)plans.append(UUID.randomUUID(),id,w.durationDays(),w.monthlyTokenQuota(),new BigDecimal(w.monthlyAmountLimit()),null);
  audit(id,actor,"UPDATED");return get(id);
 }
 /** 返回套餐。
 * @param id 套餐
  * @param version CAS
  * @param state ENABLED或DISABLED，停用不撤销历史会员
  * @param actor 操作者
  * @return 套餐 */
 @Transactional(rollbackFor=Exception.class)
 public MembershipDtos.Plan status(UUID id,long version,String state,UUID actor){var row=checked(id,version);if(row.status().equals(state))return view(row);if(plans.update(id,version,row.name(),row.code(),state,actor)!=1)throw conflict();audit(id,actor,"STATUS_CHANGED");return get(id);}
 /** 删除未被引用的资源，调用方必须先完成权限与版本检查。
 * @param id 套餐
  * @param version CAS
  * @param actor 操作者
  * @throws BusinessException 历史会员引用时拒绝；未使用套餐及其版本可物理删除 */
 @Transactional(rollbackFor=Exception.class)
 public void delete(UUID id,long version,UUID actor){checked(id,version);if(plans.references(id)>0)throw new BusinessException(409,"RESOURCE_REFERENCED","套餐已有会员引用，只能停用");plans.delete(id);audit(id,actor,"DELETED");}
 /** 返回新不可变权益。
 * @param id 套餐
  * @param version 套餐CAS
  * @param w 待生效规则
  * @param actor 操作者
  * @return 新不可变权益
  * @throws BusinessException 回溯生效或并发版本改变 */
 @Transactional(rollbackFor=Exception.class)
 public MembershipDtos.PlanVersion append(UUID id,long version,MembershipDtos.VersionWrite w,UUID actor){
  var plan=checked(id,version);if(w.effectiveFrom().isBefore(OffsetDateTime.now()))throw new BusinessException(422,"INVALID_TRANSITION","权益版本不能回溯生效");
  UUID revision=UUID.randomUUID();plans.append(revision,id,w.durationDays(),w.monthlyTokenQuota(),new BigDecimal(w.monthlyAmountLimit()),w.effectiveFrom());
  if(plans.update(id,version,plan.name(),plan.code(),plan.status(),actor)!=1)throw conflict();audit(id,actor,"VERSION_APPENDED");return versionView(plans.version(revision));
 }
 /** 返回不可变权益历史。
 * @param id 套餐
  * @param q 分页；按版本号检索，排序按追加时刻
  * @return 不可变权益历史 */
 @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
 public PageResult<MembershipDtos.PlanVersion> versions(UUID id,PageQuery q){get(id);if(q.status()!=null)throw new BusinessException(400,"INVALID_FILTER","权益版本不可启停，请省略status");return new PageResult<>(plans.versions(id,q).stream().map(this::versionView).toList(),q.page(),q.size(),plans.versionsCount(id,q));}
 private PlanMapper.Row checked(UUID id,long version){var row=plans.lock(id);if(row==null)throw missing();if(row.version()!=version)throw conflict();return row;}
 private MembershipDtos.Plan view(PlanMapper.Row r){var v=plans.current(r.id());if(v==null)throw new BusinessException(503,"DEPENDENCY_UNAVAILABLE","套餐尚无有效权益");return new MembershipDtos.Plan(r.id(),r.version(),r.createdAt(),r.updatedAt(),r.name(),r.code(),v.durationDays(),v.monthlyTokenQuota(),v.monthlyAmountLimit().toPlainString(),r.status());}
 private MembershipDtos.PlanVersion versionView(PlanMapper.Version v){return new MembershipDtos.PlanVersion(v.id(),v.versionNumber(),v.createdAt(),v.createdAt(),v.durationDays(),v.monthlyTokenQuota(),v.monthlyAmountLimit().toPlainString(),v.effectiveFrom(),v.planId());}
 private void audit(UUID id,UUID actor,String action){writes.audit(UUID.randomUUID(),actor,action,"membership_plan",id);writes.event(UUID.randomUUID(),id,"membership_plan."+action,"{\"id\":\""+id+"\"}");}
 private static BusinessException missing(){return new BusinessException(404,"RESOURCE_NOT_FOUND","套餐不存在");}
 private static BusinessException conflict(){return new BusinessException(412,"VERSION_CONFLICT","套餐已改变，请刷新");}
}
