package cn.zhixiang.platform.billing;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import cn.zhixiang.platform.chat.*;
import cn.zhixiang.platform.shared.BusinessException;
/** 在调用完成短事务中记录原返回，不允许外部客户端提供所谓真实用量。 */
@Service
public class UsageEvidenceService {
 private final UsageEvidenceMapper mapper;private final RunLeaseMapper leases;
 /** 创建证据用例。
  * @param mapper 追加存储
  * @param leases 执行器校验 */
 public UsageEvidenceService(UsageEvidenceMapper mapper,RunLeaseMapper leases){this.mapper=mapper;this.leases=leases;}
 /** 在持运行锁的完成事务内保存证据，冲突抛错而非静默覆盖。
  * @param run 运行
  * @param owner 原执行器
  * @param generation 原代次
  * @param usage 实际用量，null表示供应商明确拒绝且无费用
  * @return 是否为原执行器 */
 @Transactional(propagation=Propagation.MANDATORY)
 public boolean record(UUID run,UUID owner,long generation,ModelGateway.Usage usage){
  if(!leases.calledOwner(run,owner,generation))return false;
  String kind=usage==null?"NO_CHARGE":"USAGE";Long input=usage==null?null:usage.inputTokens(),output=usage==null?null:usage.outputTokens(),cached=usage==null?null:usage.cachedTokens();String provider=usage==null?null:usage.providerRequestId();
  if(usage!=null&&(input<0||output<0||cached<0||cached>input||provider!=null&&provider.length()>500))throw new BusinessException(422,"USAGE_INVALID","供应商用量证据不合法");
  mapper.insert(run,kind,input,output,cached,provider,owner,generation);var existing=mapper.get(run);
  if(!existing.kind().equals(kind)||!Objects.equals(input,existing.inputTokens())||!Objects.equals(output,existing.outputTokens())||!Objects.equals(cached,existing.cachedTokens())||!Objects.equals(provider,existing.providerRequestId())||!owner.equals(existing.leaseOwner())||generation!=existing.generation())throw new BusinessException(409,"USAGE_EVIDENCE_CONFLICT","同一运行返回的用量证据冲突");return true;
 }
}
