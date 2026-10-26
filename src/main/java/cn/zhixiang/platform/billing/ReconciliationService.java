package cn.zhixiang.platform.billing;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import cn.zhixiang.platform.chat.RunJournalMapper;
import cn.zhixiang.platform.identity.Actor;
import cn.zhixiang.platform.operations.*;
import cn.zhixiang.platform.shared.*;
/** 核对任务用例；只处理原调用可信返回，无证据时明确失败且保留冻结。 */
@Service
public class ReconciliationService {
 private final ReconciliationMapper mapper;private final UsageEvidenceMapper evidence;private final QuotaMapper accounts;private final QuotaService quotas;private final RunJournalMapper runs;private final JobService jobs;private final WriteMapper writes;
 /** 创建用例。
  * @param mapper 核对任务
  * @param evidence 可信调用证据
  * @param accounts 账户锁
  * @param quotas 结算规则
  * @param runs 运行锁
  * @param jobs 任务投影
  * @param writes 审计与outbox */
 public ReconciliationService(ReconciliationMapper mapper,UsageEvidenceMapper evidence,QuotaMapper accounts,QuotaService quotas,RunJournalMapper runs,JobService jobs,WriteMapper writes){this.mapper=mapper;this.evidence=evidence;this.accounts=accounts;this.quotas=quotas;this.runs=runs;this.jobs=jobs;this.writes=writes;}
 /** 创建核对任务，已有排队任务复用；已经结算或释放不能重新发起。
  * @param run 运行
  * @param reason 原因
  * @param actor 管理员，入口另校验billing:reconcile
  * @return 可查询任务 */
 @Transactional(rollbackFor=Exception.class)
 public JobDtos.View create(UUID run,String reason,Actor actor){
  if(!"admin".equals(actor.platform()))throw new BusinessException(403,"FORBIDDEN","仅管理员可核对用量");lock(run);
  if(!"RECONCILING".equals(accounts.hold(run).state()))throw new BusinessException(409,"RECONCILIATION_NOT_REQUIRED","运行不处于待核对账务状态");
  UUID active=mapper.active(run);if(active!=null)return jobs.get(active);UUID id=UUID.randomUUID();mapper.insert(id,run,reason,actor.id());writes.auditReason(UUID.randomUUID(),actor.id(),"RECONCILIATION_REQUESTED","generation_run",run,reason);writes.event(UUID.randomUUID(),id,"billing.RECONCILIATION_REQUESTED","{\"id\":\""+id+"\"}");return jobs.get(id);
 }
 /** 处理一个任务，整个处理仅含数据库操作；崩溃回滚后可重做，不重发供应商请求。
  * @return 是否处理了任务 */
 @Transactional(rollbackFor=Exception.class)
 public boolean processOne(){
  var candidate=mapper.candidate();if(candidate==null)return false;lock(candidate.runId());var task=mapper.lock(candidate.id());if(!"QUEUED".equals(task.state()))return false;
  var hold=accounts.hold(task.runId());String error=null;
  if("RECONCILING".equals(hold.state())){
   var proof=evidence.get(task.runId());
   if(proof==null)error="PROVIDER_RECONCILIATION_UNAVAILABLE";
   else if("NO_CHARGE".equals(proof.kind()))quotas.release(task.runId(),true);
   else quotas.settle(task.runId(),proof.inputTokens(),proof.outputTokens(),proof.cachedTokens());
  }else if(!"SETTLED".equals(hold.state())&&!"RELEASED".equals(hold.state()))error="RECONCILIATION_STATE_CONFLICT";
  mapper.finish(task.id(),error==null?"SUCCEEDED":"FAILED",error);
  writes.audit(UUID.randomUUID(),task.createdBy(),error==null?"RECONCILED":"RECONCILIATION_UNAVAILABLE","generation_run",task.runId());writes.event(UUID.randomUUID(),task.id(),"billing.RECONCILIATION_FINISHED","{\"id\":\""+task.id()+"\"}");return true;
 }
 private void lock(UUID run){var hold=accounts.hold(run);if(hold==null)throw new BusinessException(404,"RESOURCE_NOT_FOUND","运行账务不存在");accounts.lock(hold.accountId());if(runs.lock(run)==null)throw new BusinessException(404,"RESOURCE_NOT_FOUND","运行不存在");}
}
