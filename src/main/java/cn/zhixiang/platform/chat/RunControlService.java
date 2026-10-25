package cn.zhixiang.platform.chat;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import cn.zhixiang.platform.billing.*;
import cn.zhixiang.platform.identity.Actor;
import cn.zhixiang.platform.shared.*;
/** 取消和调用失联处理；未知结果只冻结，不自动补发供应商请求。 */
@Service
public class RunControlService {
 private final RunMapper mapper;private final RunStreamAccess access;private final QuotaMapper accounts;private final QuotaService quotas;private final RunJournalMapper heads;private final RunLeaseMapper leases;private final RunJournal journal;private final WriteMapper writes;
 /** 创建控制用例。
 * @param mapper 状态
 * @param access 所有权
 * @param accounts 账户锁
 * @param quotas 账务
 * @param heads 运行锁
 * @param leases 过期候选
 * @param journal 事件
 * @param writes 审计 */
 public RunControlService(RunMapper mapper,RunStreamAccess access,QuotaMapper accounts,QuotaService quotas,RunJournalMapper heads,RunLeaseMapper leases,RunJournal journal,WriteMapper writes){this.mapper=mapper;this.access=access;this.accounts=accounts;this.quotas=quotas;this.heads=heads;this.leases=leases;this.journal=journal;this.writes=writes;}
 /** 取消本人运行；已完成保持原结果。调用起点之后取消不能推定无费用。
 * @param run 运行
 * @param reason 原因
 * @param actor 学生
 * @return 当前状态 */
 @Transactional(rollbackFor=Exception.class)
 public RunDtos.View cancel(UUID run,String reason,Actor actor){if(!access.allowed(run,actor))throw new BusinessException(404,"RESOURCE_NOT_FOUND","运行不存在");var head=lock(run);if(!Set.of("RESERVED","RUNNING").contains(head.state()))return mapper.view(run);if(head.state().equals("RESERVED"))quotas.release(run,true);else quotas.unknown(run);terminal(run,"cancelled","CANCELLED","RUN_CANCELLED");writes.auditReason(UUID.randomUUID(),actor.id(),"CANCELLED","generation_run",run,reason);return mapper.view(run);}
 /** 处理一个租约过期的已开始调用。重新加锁检查，避免与正常完成竞争覆盖。
 * @return 是否处理了候选 */
 @Transactional(rollbackFor=Exception.class)
 public boolean expireOne(){UUID run=leases.expiredCall();if(run==null)return false;var head=lock(run);if(!head.state().equals("RUNNING")||!leases.expired(run))return false;quotas.unknown(run);terminal(run,"failed","FAILED","RUN_OUTCOME_UNKNOWN");writes.event(UUID.randomUUID(),run,"run.RECONCILING","{\"id\":\""+run+"\"}");return true;}
 private RunJournalMapper.Head lock(UUID run){var hold=accounts.hold(run);if(hold==null)throw new BusinessException(404,"RESOURCE_NOT_FOUND","运行预占不存在");accounts.lock(hold.accountId());var head=heads.lock(run);if(head==null)throw new BusinessException(404,"RESOURCE_NOT_FOUND","运行不存在");return head;}
 private void terminal(UUID run,String type,String state,String code){var data=new LinkedHashMap<String,Object>();data.put("state",state);data.put("messageId",null);data.put("errorCode",code);journal.append(run,type,data);leases.terminal(run,state,code,null);}
}
