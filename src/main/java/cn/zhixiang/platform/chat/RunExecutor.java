package cn.zhixiang.platform.chat;
import java.util.UUID;
import org.springframework.stereotype.Service;
/** 事务外模型调用协调器，每个运行最多发起一次；未知结果交给核对而不是自动重试。 */
@Service
public class RunExecutor {
 private final UUID owner=UUID.randomUUID();private final RunLeaseService leases;private final RunExecution execution;private final RunCompletionService completion;private final ModelGateway gateway;
 /** 创建执行器。
 * @param leases 领取/起点
 * @param execution 上下文和增量
 * @param completion 结束事务
 * @param gateway 外部HTTP */
 public RunExecutor(RunLeaseService leases,RunExecution execution,RunCompletionService completion,ModelGateway gateway){this.leases=leases;this.execution=execution;this.completion=completion;this.gateway=gateway;}
 /** 处理至多一个任务；无运行返回false，基础设施异常抛出并保留数据库断点。
 * @return 是否领取了任务 */
 @org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.NEVER)
 public boolean runOne(){var lease=leases.claim(owner);if(lease==null)return false;ModelGateway.Request request;
  try{request=execution.prepare(lease.id());}catch(ModelGateway.Failure failure){completion.rejectBeforeCall(lease.id(),owner,lease.generation(),failure.code());return true;}
  if(!leases.start(lease.id(),owner,lease.generation()))return true;var output=new StringBuilder();
  try{var usage=gateway.stream(request,part->{if(output.length()+part.length()>262144)throw new ModelGateway.Failure("MODEL_OUTPUT_LIMIT",false);output.append(part);for(int offset=0;offset<part.length();){int end=part.offsetByCodePoints(offset,Math.min(500,part.codePointCount(offset,part.length())));execution.delta(lease.id(),owner,lease.generation(),part.substring(offset,end));offset=end;}});completion.success(lease.id(),owner,lease.generation(),output.toString(),usage);}
  catch(ModelGateway.Failure failure){completion.failure(lease.id(),owner,lease.generation(),failure);}return true;
 }
}
