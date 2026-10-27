package cn.zhixiang.platform.exporting;
import java.util.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import cn.zhixiang.platform.identity.*;
import cn.zhixiang.platform.configuration.*;
import cn.zhixiang.platform.operations.*;
import cn.zhixiang.platform.storage.ObjectStorage;
import cn.zhixiang.platform.asset.AssetDtos;
import cn.zhixiang.platform.shared.*;
/** 异步报告编排；数据库只持元数据，网络写对象不占数据库事务。 */
@Service
public class ExportService {
 private final ExportMapper mapper;private final ExportDataMapper data;private final IdentityMapper identity;private final StorageMapper stores;private final ObjectStorage storage;private final CsvReport csv;private final JobService jobs;private final WriteMapper writes;private final TransactionTemplate tx,snapshot;private final String configured;
 /** 注入报告端口与显式事务边界。
  * @param mapper 任务仓储
  * @param data 白名单数据
  * @param identity 当前权限
  * @param stores 存储配置
  * @param storage 对象端口
  * @param csv 安全CSV
  * @param jobs 任务视图
  * @param writes 审计
  * @param manager 事务管理器
  * @param configured 私有导出存储ID，未设置回退上传存储 */
 public ExportService(ExportMapper mapper,ExportDataMapper data,IdentityMapper identity,StorageMapper stores,ObjectStorage storage,CsvReport csv,JobService jobs,WriteMapper writes,PlatformTransactionManager manager,@Value("${ZX_EXPORT_STORAGE_ID:${ZX_UPLOAD_STORAGE_ID:}}") String configured){this.mapper=mapper;this.data=data;this.identity=identity;this.stores=stores;this.storage=storage;this.csv=csv;this.jobs=jobs;this.writes=writes;this.configured=configured;tx=new TransactionTemplate(manager);snapshot=new TransactionTemplate(manager);snapshot.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);}
 /** 创建持久任务；幂等由调用入口事务负责，不在请求内生成文件。
  * @param w 请求
  * @param actor 当前身份
  * @return 排队任务
  * @throws BusinessException 权限、日期或配置不满足 */
 @Transactional(rollbackFor=Exception.class)
 public JobDtos.View create(ExportDtos.Request w,Actor actor){dates(w);authorize(actor.id(),actor.platform(),w.kind(),false);if(mapper.saturated(actor.id()))throw new BusinessException(429,"EXPORT_RATE_LIMITED","导出任务积压或申请过于频繁，请稍后重试");UUID store;try{store=UUID.fromString(configured);}catch(Exception e){throw new BusinessException(503,"EXPORT_STORAGE_UNAVAILABLE","未配置私有导出存储");}var s=stores.lock(store);target(s);UUID id=UUID.randomUUID();mapper.insert(id,actor.id(),actor.platform(),w,store);writes.audit(UUID.randomUUID(),actor.id(),"EXPORT_REQUESTED","export_job",id);return jobs.get(id);}
 /** 当前申请者读取任务；中介不得读取其他人的运维任务。
  * @param id 任务
  * @param actor 当前身份
  * @return 任务元数据 */
 @Transactional(readOnly=true)
 public JobDtos.View get(UUID id,Actor actor){owned(id,actor);return jobs.get(id);}
 /** 重新验证申请者、业务权限及报告内所有订单归属后签发一分钟下载。
  * @param id 任务
  * @param actor 当前身份
  * @return 私有下载授权
  * @throws BusinessException 未完成、过期、归属或权限变化 */
 @Transactional(rollbackFor=Exception.class)
 public AssetDtos.Download download(UUID id,Actor actor){var r=owned(id,actor);authorize(r.createdBy(),r.platform(),r.kind(),r.includesChat());if(r.platform().equals("partner")&&mapper.scopeChanged(id,actor.id()))denied();if(!r.state().equals("SUCCEEDED")||r.resultExpiresAt()==null||!r.resultExpiresAt().isAfter(OffsetDateTime.now()))throw new BusinessException(422,"EXPORT_NOT_AVAILABLE","报告尚未完成或已过期");var grant=storage.download(target(stores.get(r.storageId())),r.objectKey(),r.objectVersion());writes.audit(UUID.randomUUID(),actor.id(),"EXPORT_DOWNLOAD","export_job",id);return new AssetDtos.Download(grant.url(),grant.expiresAt());}
 /** 独立worker执行一个任务；失效租约仅允许重建报告，不会覆盖旧对象。
  * @return 是否领取了任务 */
 public boolean runOne(){var r=tx.execute(s->{var claimed=mapper.claim();if(claimed!=null)mapper.attempt(claimed.id(),claimed.generation(),key(claimed));return claimed;});if(r==null)return false;try{if(r.attempts()>3)throw new BusinessException(422,"EXPORT_ATTEMPTS_EXHAUSTED","租约恢复次数超限，请重新申请");var prepared=snapshot.execute(s->prepare(r));tx.executeWithoutResult(s->{var latest=mapper.lock(r.id());if(latest.generation()!=r.generation()||!latest.state().equals("RUNNING")||latest.leaseUntil().isBefore(OffsetDateTime.now()))throw new BusinessException(409,"EXPORT_LEASE_LOST","导出租约已失效");});var metadata=storage.write(prepared.target(),key(r),prepared.bytes(),"text/csv");tx.executeWithoutResult(s->{mapper.objectVersion(r.id(),r.generation(),metadata.versionId());authorize(r.createdBy(),r.platform(),r.kind(),prepared.chat());if(r.platform().equals("partner")&&mapper.scopeChanged(r.id(),r.createdBy()))denied();if(mapper.finish(r.id(),r.generation(),key(r),metadata.versionId())==1)writes.audit(UUID.randomUUID(),r.createdBy(),"EXPORT_COMPLETED","export_job",r.id());});}catch(BusinessException e){tx.executeWithoutResult(s->mapper.fail(r.id(),r.generation(),e.code()));}catch(Exception e){tx.executeWithoutResult(s->mapper.fail(r.id(),r.generation(),"EXPORT_FAILED"));}return true;}
 private Prepared prepare(ExportMapper.Row r){var current=mapper.lock(r.id());if(current.generation()!=r.generation()||!current.state().equals("RUNNING")||current.leaseUntil().isBefore(OffsetDateTime.now()))throw new BusinessException(409,"EXPORT_LEASE_LOST","导出租约已失效");authorize(r.createdBy(),r.platform(),r.kind(),false);boolean chat=r.kind().equals("AUDIT")&&identity.permissions(r.createdBy()).contains("chat:audit");var start=r.fromDate().atStartOfDay(ZoneId.of("Asia/Shanghai")).toOffsetDateTime();var end=r.toDate().plusDays(1).atStartOfDay(ZoneId.of("Asia/Shanghai")).toOffsetDateTime();boolean admin=r.platform().equals("admin");var rows=switch(r.kind()){case "ORDERS"->data.orders(start,end,r.createdBy(),admin);case "PROJECTS"->data.projects(start,end,r.createdBy(),admin);case "USAGE"->data.usage(start,end);case "AUDIT"->data.audits(start,end,chat);default->throw new IllegalStateException("未知导出类型");};byte[] bytes=csv.render(r.kind(),rows);mapper.clearScope(r.id());rows.stream().map(ExportDataMapper.Data::orderId).filter(Objects::nonNull).distinct().forEach(order->mapper.scope(r.id(),order));mapper.chat(r.id(),chat);return new Prepared(target(stores.get(r.storageId())),bytes,chat);}
 private ExportMapper.Row owned(UUID id,Actor actor){var r=mapper.get(id);if(r==null||!r.createdBy().equals(actor.id())||!r.platform().equals(actor.platform()))throw new BusinessException(404,"RESOURCE_NOT_FOUND","导出任务不存在");return r;}
 private void authorize(UUID actor,String platform,String kind,boolean chat){if(!Set.of("admin","partner").contains(platform)||!platform.equals(mapper.platform(actor)))denied();if(platform.equals("partner")&&!Set.of("ORDERS","PROJECTS").contains(kind))denied();var permissions=identity.permissions(actor);String domain=switch(kind){case "ORDERS"->"orders";case "PROJECTS"->"projects";case "USAGE"->"usage";case "AUDIT"->"audits";default->throw new IllegalArgumentException();};if(!permissions.contains("post_"+platform+"_exports")||!permissions.contains(kind.equals("USAGE")?"billing:read":"get_"+platform+"_"+domain)||chat&&!permissions.contains("chat:audit"))denied();}
 private ObjectStorage.Target target(StorageMapper.Row s){if(s==null||s.status()!=StorageDtos.Status.ENABLED||s.access()!=StorageDtos.Access.PRIVATE)throw new BusinessException(503,"EXPORT_STORAGE_UNAVAILABLE","导出存储不可用");return new ObjectStorage.Target(s.provider().name(),s.endpoint(),s.region(),s.bucket(),s.credentialRef());}
 private String key(ExportMapper.Row r){return "exports/"+r.id()+"/"+r.generation();}
 private void dates(ExportDtos.Request w){if(w.to().isBefore(w.from())||ChronoUnit.DAYS.between(w.from(),w.to())>365||w.from().isBefore(LocalDate.of(2000,1,1))||w.to().isAfter(LocalDate.now(ZoneId.of("Asia/Shanghai"))))throw new BusinessException(400,"INVALID_FILTER","导出日期范围不合法或超过366天");}
 private void denied(){throw new BusinessException(403,"EXPORT_SCOPE_REVOKED","导出业务权限或数据归属已变化");}
 private record Prepared(ObjectStorage.Target target,byte[] bytes,boolean chat){}
}
