package cn.zhixiang.platform.membership;
import cn.zhixiang.platform.shared.*;
import cn.zhixiang.platform.identity.AuthMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.*;
import java.time.OffsetDateTime;
/** 会员授予/撤销，锁顺序为学生账户→套餐；与历史引用保护、权益快照在一个事务提交。 */
@Service
public class MemberService {
 private final cn.zhixiang.platform.identity.UserMapper users;private final MemberMapper members;private final PlanMapper plans;private final AuthMapper accounts;private final WriteMapper writes;
 /** 初始化模块组件，所需端口和配置由构造器注入。
 * @param users 只读账户目录
  * @param members 会员端口
  * @param plans 权益版本
  * @param accounts 学生账户锁
  * @param writes 审计和outbox */
 public MemberService(cn.zhixiang.platform.identity.UserMapper users,MemberMapper members,PlanMapper plans,AuthMapper accounts,WriteMapper writes){this.users=users;this.members=members;this.plans=plans;this.accounts=accounts;this.writes=writes;}
 /** 返回会员记录页。
 * @param user 学生标识
  * @param q 分页查询
  * @param state 会员状态，可空
  * @return 会员记录页 */
 @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
 public PageResult<MembershipDtos.Member> list(UUID user,PageQuery q,String state){if(users.get(user)==null)throw missing();if(state!=null&&!Set.of("SCHEDULED","ACTIVE","EXPIRED","REVOKED").contains(state))throw new BusinessException(400,"INVALID_FILTER","会员状态无效");return new PageResult<>(members.list(user,q,state).stream().map(this::view).toList(),q.page(),q.size(),members.count(user,q,state));}
 /** 返回现在有效的会员。
 * @param user 服务端认证的当前学生
  * @return 现在有效的会员
  * @throws BusinessException 无有效会员返回404 */
 public MembershipDtos.Member current(UUID user){var row=members.active(user);if(row==null)throw new BusinessException(404,"RESOURCE_NOT_FOUND","当前没有有效会员");return view(row);}
 /** 返回新会员。
 * @param user 目标学生
  * @param w 计划与起始时间
  * @param actor 管理员
  * @return 新会员
  * @throws BusinessException 停用/非学生/期间重叠/无已生效套餐 */
 @Transactional(rollbackFor=Exception.class)
 public MembershipDtos.Member grant(UUID user,MembershipDtos.Grant w,UUID actor){
  var account=accounts.lockAccount(user);if(account==null)throw missing();if(!account.platform().equals("student")||!account.status().equals("ENABLED"))throw new BusinessException(422,"INVALID_REFERENCE","只能授予启用的学生账户");
  var plan=plans.lock(w.planId());if(plan==null||!plan.status().equals("ENABLED"))throw new BusinessException(422,"INVALID_REFERENCE","套餐不存在或停用");
  var revision=plans.current(plan.id());if(revision==null)throw new BusinessException(422,"INVALID_REFERENCE","套餐无已生效权益");
  OffsetDateTime end=w.startsAt().plusDays(revision.durationDays());if(!end.isAfter(OffsetDateTime.now()))throw new BusinessException(422,"INVALID_PERIOD","不能发放已过期会员");
  if(members.overlap(user,w.startsAt(),end)>0)throw new BusinessException(409,"MEMBERSHIP_OVERLAP","会员期间重叠，请选择现有会员结束后的时刻");
  UUID id=UUID.randomUUID();members.insert(id,user,plan.id(),revision.id(),w.startsAt(),end,actor);audit(id,actor,"GRANTED",w.reason());return view(members.get(id));
 }
 /** 返回撤销后的历史记录；同值幂等不产生新事件。
 * @param id 会员
  * @param version CAS
  * @param reason 必填原因
  * @param actor 管理员
  * @return 撤销后的历史记录；同值幂等不产生新事件 */
 @Transactional(rollbackFor=Exception.class)
 public MembershipDtos.Member revoke(UUID id,long version,String reason,UUID actor){
  var found=members.get(id);if(found==null)throw missing();accounts.lockAccount(found.userId());var row=members.get(id);
  if(row.version()!=version)throw new BusinessException(412,"VERSION_CONFLICT","会员已改变");if(row.state().equals("REVOKED"))return view(row);
  if(row.state().equals("EXPIRED"))throw new BusinessException(422,"INVALID_TRANSITION","会员已到期，无需撤销");
  if(members.revoke(id,version)!=1)throw new BusinessException(412,"VERSION_CONFLICT","会员已改变");audit(id,actor,"REVOKED",reason);return view(members.get(id));
 }
 private MembershipDtos.Member view(MemberMapper.Row r){return new MembershipDtos.Member(r.id(),r.version(),r.createdAt(),r.updatedAt(),r.userId(),r.planId(),r.startsAt(),r.expiresAt(),r.state());}
 private void audit(UUID id,UUID actor,String action,String reason){writes.auditReason(UUID.randomUUID(),actor,action,"membership",id,reason);writes.event(UUID.randomUUID(),id,"membership."+action,"{\"id\":\""+id+"\"}");}
 private static BusinessException missing(){return new BusinessException(404,"RESOURCE_NOT_FOUND","学生或会员不存在");}
}
