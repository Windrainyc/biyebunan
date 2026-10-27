package cn.zhixiang.platform.probing;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import cn.zhixiang.platform.identity.*;
import cn.zhixiang.platform.model.ModelMapper;
import cn.zhixiang.platform.configuration.*;
import cn.zhixiang.platform.storage.ObjectStorage;
import cn.zhixiang.platform.operations.*;
import cn.zhixiang.platform.shared.*;
/** 配置探测编排；网络在事务外，结果仅代表指定版本的只读检查。 */
@Service
public class ConfigurationProbeService {
 private final ProbeMapper mapper;private final ModelMapper models;private final StorageMapper stores;private final DomainMapper domains;private final IdentityMapper identity;private final SecretVault vault;private final ObjectStorage storage;private final MetadataProbe metadata;private final JobService jobs;private final WriteMapper writes;private final TransactionTemplate tx;
 /** 注入配置、授权和外部探测端口。
  * @param mapper 任务仓储
  * @param models 模型配置
  * @param stores 存储配置
  * @param domains 域名配置
  * @param identity 当前权限
  * @param vault 凭证解密
  * @param storage 存储端口
  * @param metadata 只读网络探测
  * @param jobs 通用任务视图
  * @param writes 审计
  * @param manager 事务管理器 */
 public ConfigurationProbeService(ProbeMapper mapper,ModelMapper models,StorageMapper stores,DomainMapper domains,IdentityMapper identity,SecretVault vault,ObjectStorage storage,MetadataProbe metadata,JobService jobs,WriteMapper writes,PlatformTransactionManager manager){this.mapper=mapper;this.models=models;this.stores=stores;this.domains=domains;this.identity=identity;this.vault=vault;this.storage=storage;this.metadata=metadata;this.jobs=jobs;this.writes=writes;tx=new TransactionTemplate(manager);}
 /** 创建有界持久任务，HTTP幂等入口负责串行化同一账户。
  * @param kind 类型
  * @param target 配置标识
  * @param reason 审计原因
  * @param actor 管理员
  * @return 排队任务
  * @throws BusinessException 无权限、资源不存在或限流 */
 @Transactional(rollbackFor=Exception.class)
 public JobDtos.View create(String kind,UUID target,String reason,Actor actor){authorize(kind,actor.id());var config=config(kind,target);if(mapper.saturated(actor.id()))throw new BusinessException(429,"PROBE_RATE_LIMITED","探测任务过多，请稍后重试");UUID id=UUID.randomUUID();mapper.insert(id,kind,target,config.version(),actor.id(),reason);writes.audit(UUID.randomUUID(),actor.id(),"PROBE_REQUESTED","configuration_probe_job",id);return jobs.get(id);}
 /** 领取一个任务；只读请求允许租约恢复，最多三次，不发付费推理。
  * @return 是否处理任务 */
 public boolean runOne(){var row=tx.execute(s->mapper.claim());if(row==null)return false;try{if(row.attempts()>3)throw fail("PROBE_ATTEMPTS_EXHAUSTED");var prepared=tx.execute(s->{authorize(row.kind(),row.createdBy());var c=config(row.kind(),row.targetId());if(c.version()!=row.targetVersion())throw fail("PROBE_CONFIG_CHANGED");return c;});switch(row.kind()){case "MODEL"->{String secret=prepared.key()==null?null:vault.get(prepared.key().secretRef());if(secret==null)throw fail("MODEL_KEY_UNAVAILABLE");metadata.model(prepared.endpoint(),prepared.code(),secret);}case "STORAGE"->storage.probe(prepared.storage());case "DOMAIN"->metadata.domain(prepared.endpoint());default->throw fail("PROBE_KIND_INVALID");}tx.executeWithoutResult(s->{authorize(row.kind(),row.createdBy());var current=config(row.kind(),row.targetId());if(current.version()!=row.targetVersion()||!Objects.equals(current.key(),prepared.key()))throw fail("PROBE_CONFIG_CHANGED");if(mapper.finish(row.id(),row.generation(),null)==1)writes.audit(UUID.randomUUID(),row.createdBy(),"PROBE_SUCCEEDED","configuration_probe_job",row.id());});}catch(BusinessException e){tx.executeWithoutResult(s->mapper.finish(row.id(),row.generation(),e.code()));}catch(Exception e){tx.executeWithoutResult(s->mapper.finish(row.id(),row.generation(),"PROBE_FAILED"));}return true;}
 private Config config(String kind,UUID id){return switch(kind){case "MODEL"->{var r=models.lock(id);if(r==null)throw missing();if(!r.status().name().equals("ENABLED"))throw fail("PROBE_CONFIG_DISABLED");yield new Config(r.version(),r.endpoint(),r.code(),null,mapper.key(id));}case "STORAGE"->{var r=stores.lock(id);if(r==null)throw missing();if(!r.status().name().equals("ENABLED"))throw fail("PROBE_CONFIG_DISABLED");yield new Config(r.version(),null,null,new ObjectStorage.Target(r.provider().name(),r.endpoint(),r.region(),r.bucket(),r.credentialRef()),null);}case "DOMAIN"->{var r=domains.lock(id);if(r==null)throw missing();if(!r.status().name().equals("ENABLED"))throw fail("PROBE_CONFIG_DISABLED");yield new Config(r.version(),r.hostname(),null,null,null);}default->throw fail("PROBE_KIND_INVALID");};}
 private void authorize(String kind,UUID actor){String path=switch(kind){case "MODEL"->"models";case "STORAGE"->"storages";case "DOMAIN"->"domains";default->throw fail("PROBE_KIND_INVALID");};if(!mapper.enabled(actor)||!identity.permissions(actor).contains("post_admin_"+path+"_id_test"))throw new BusinessException(403,"PROBE_PERMISSION_REVOKED","探测权限已失效");}
 private BusinessException missing(){return new BusinessException(404,"RESOURCE_NOT_FOUND","配置不存在");}
 private BusinessException fail(String code){return new BusinessException(422,code,"配置探测条件不满足");}
 private record Config(long version,String endpoint,String code,ObjectStorage.Target storage,ProbeMapper.Key key){}
}
