package cn.zhixiang.platform.exporting;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import cn.zhixiang.platform.configuration.StorageMapper;
import cn.zhixiang.platform.storage.ObjectStorage;
import cn.zhixiang.platform.shared.BusinessException;
/** 导出专用对象回收；网络调用在事务外，失败保留任务供下一次租约重试。 */
@Service
public class ExportCleanup {
 private final ExportCleanupMapper mapper;private final StorageMapper stores;private final ObjectStorage storage;private final TransactionTemplate tx;
 /** 注入回收端口。
  * @param mapper 回收候选
  * @param stores 配置
  * @param storage 对象端口
  * @param manager 事务管理器 */
 public ExportCleanup(ExportCleanupMapper mapper,StorageMapper stores,ObjectStorage storage,PlatformTransactionManager manager){this.mapper=mapper;this.stores=stores;this.storage=storage;tx=new TransactionTemplate(manager);}
 /** 清理一个明确登记且过期的对象；一小时缓冲大于对象客户端120秒硬超时。
  * @return 是否领取候选 */
 public boolean runOne(){var r=tx.execute(s->mapper.claim());if(r==null)return false;try{var s=stores.get(r.storageId());if(s==null)return true;var target=new ObjectStorage.Target(s.provider().name(),s.endpoint(),s.region(),s.bucket(),s.credentialRef());String version=r.objectVersion();if(version==null)version=storage.stat(target,r.objectKey()).versionId();storage.remove(target,r.objectKey(),version);tx.executeWithoutResult(t->mapper.finish(r.jobId(),r.generation()));}catch(BusinessException e){if(e.code().equals("ASSET_OBJECT_MISSING"))tx.executeWithoutResult(t->mapper.finish(r.jobId(),r.generation()));}return true;}
}
