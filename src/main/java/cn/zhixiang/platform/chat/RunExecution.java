package cn.zhixiang.platform.chat;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import cn.zhixiang.platform.identity.*;
import tools.jackson.databind.json.JsonMapper;
/** 执行前准备与增量短事务；本类不发网络请求。 */
@Service
public class RunExecution {
 private final RunMapper mapper;private final RunSourceAccess sources;private final IdentityMapper identity;private final SecretVault secrets;private final RunLeaseMapper leases;private final RunJournalMapper heads;private final RunJournal journal;private final ModelGateway gateway;private final JsonMapper json;
 /** 创建执行端口。
 * @param mapper 上下文
 * @param sources 引用授权
 * @param identity 当前权限
 * @param secrets 密钥解密
 * @param leases 代次
 * @param heads 运行锁
 * @param journal 日志
 * @param gateway 配置预检
 * @param json 解码 */
 public RunExecution(RunMapper mapper,RunSourceAccess sources,IdentityMapper identity,SecretVault secrets,RunLeaseMapper leases,RunJournalMapper heads,RunJournal journal,ModelGateway gateway,JsonMapper json){this.mapper=mapper;this.sources=sources;this.identity=identity;this.secrets=secrets;this.leases=leases;this.heads=heads;this.journal=journal;this.gateway=gateway;this.json=json;}
 /** 读取固定上下文并解密当前密钥，退出事务后才允许发请求。
 * @param run 运行
 * @return 私有请求，禁止日志打印 */
 @Transactional(readOnly=true)
 public ModelGateway.Request prepare(UUID run){authorized(run,true);var context=mapper.contextFor(run);if(context==null||!context.status().equals("ENABLED"))throw new ModelGateway.Failure("MODEL_CONFIGURATION_INVALID",true);gateway.check(context.endpoint());String secret=secrets.get(context.secretRef());if(secret==null)throw new ModelGateway.Failure("MODEL_CONFIGURATION_INVALID",true);var turns=new ArrayList<ModelGateway.Turn>();for(var t:json.readTree(context.messages()))turns.add(new ModelGateway.Turn(t.get("role").asText(),t.get("content").asText()));return new ModelGateway.Request(run,context.endpoint(),context.modelCode(),secret,turns,context.maxOutputTokens());}
 /** 增量追加重新验证当前数据权限与fencing；供应商可能已计费，失败只能记未知。
 * @param run 运行
 * @param owner 持有者
 * @param generation 代次
 * @param text 增量 */
 @Transactional(rollbackFor=Exception.class)
 public void delta(UUID run,UUID owner,long generation,String text){var head=heads.lock(run);if(head==null||!head.state().equals("RUNNING")||!leases.owner(run,owner,generation))throw new ModelGateway.Failure("RUN_FENCED",false);authorized(run,false);journal.append(run,"delta",Map.of("text",text));}
 private void authorized(UUID run,boolean beforeCall){UUID student=mapper.executorStudent(run);if(student==null||!sources.allowed(run,student)||!identity.permissions(student).contains("post_student_runs"))throw new ModelGateway.Failure("RUN_PERMISSION_CHANGED",beforeCall);}
}
