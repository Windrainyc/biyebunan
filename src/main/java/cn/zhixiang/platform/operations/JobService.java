package cn.zhixiang.platform.operations;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import cn.zhixiang.platform.shared.*;
/** 运维任务恢复：已完成、运行中、恶意文件拒绝或已删除资产均不能强行重试放行。 */
@Service
public class JobService {
 private final JobMapper mapper;private final WriteMapper writes;
 /** 创建用例。
 * @param mapper 任务端口
 * @param writes 审计事件 */
 public JobService(JobMapper mapper,WriteMapper writes){this.mapper=mapper;this.writes=writes;}
 /** 一致分页。
 * @param q 条件
 * @return 分页 */
 @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
 public PageResult<JobDtos.View> list(JobDtos.Query q){return new PageResult<>(mapper.list(q).stream().map(this::view).toList(),q.page(),q.size(),mapper.count(q));}
 /** 查询任务。
 * @param id ID
 * @return 任务 */
 public JobDtos.View get(UUID id){var r=mapper.get(id);if(r==null)missing();return view(r);}
 /** 受控恢复技术失败；不直接设置资产READY，不覆盖感染判定。
 * @param id 任务
 * @param version 期望版本
 * @param reason 原因
 * @param actor 管理员
 * @return 已排队任务 */
 @Transactional(rollbackFor=Exception.class)
 public JobDtos.View retry(UUID id,long version,String reason,UUID actor){var kind=mapper.get(id);if(kind==null)missing();if(kind.kind().equals("BILLING_RECONCILIATION"))throw new BusinessException(422,"JOB_RECONCILIATION_REQUIRED","账务核对须通过运行核对接口重新发起，以重新检查证据和账务状态");if(!kind.kind().equals("ASSET_VALIDATION"))throw new BusinessException(422,"JOB_REINDEX_REQUIRED","知识索引须通过内容重建接口恢复，以重新校验发布代次");if(mapper.lock(id)==null)missing();var r=mapper.get(id);if(r.version()!=version)throw new BusinessException(412,"VERSION_CONFLICT","任务状态已更新");if(mapper.retry(id,version)!=1)throw new BusinessException(422,"JOB_NOT_RETRYABLE","任务不是可恢复的隔离技术失败");mapper.event(UUID.randomUUID(),id,actor,reason,r.errorCode());writes.audit(UUID.randomUUID(),actor,"RETRY","job",id);writes.event(UUID.randomUUID(),id,"job.RETRY","{\"id\":\""+id+"\"}");return get(id);}
 private JobDtos.View view(JobMapper.Row r){return new JobDtos.View(r.id(),r.version(),r.createdAt(),r.updatedAt(),r.kind(),r.state(),r.progress(),r.resultAssetId(),r.errorCode());}
 private void missing(){throw new BusinessException(404,"RESOURCE_NOT_FOUND","任务不存在");}
}
