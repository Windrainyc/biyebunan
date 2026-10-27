package cn.zhixiang.platform.probing;
import java.util.UUID;
import org.apache.ibatis.annotations.*;
/** 配置探测任务仓储；租约代次阻止过期执行器写回。 */
@Mapper
public interface ProbeMapper {
 /** 创建任务。
  * @param id 任务标识
  * @param kind 配置类别
  * @param target 配置标识
  * @param version 配置版本
  * @param actor 管理员
  * @param reason 探测原因 */
 @Insert("INSERT INTO configuration_probe_job(id,kind,target_id,target_version,created_by,reason) VALUES(#{id},#{kind},#{target},#{version},#{actor},#{reason})")
 void insert(UUID id,String kind,UUID target,long version,UUID actor,String reason);
 /** 限制单人积压与小时申请数，调用者持账户幂等锁。
  * @param actor 申请者
  * @return 是否限流 */
 @Select("SELECT count(*) FILTER(WHERE state IN ('QUEUED','RUNNING'))>=3 OR count(*) FILTER(WHERE created_at>now()-interval '1 hour')>=20 FROM configuration_probe_job WHERE created_by=#{actor}")
 boolean saturated(UUID actor);
 /** 原子领取或恢复过期任务。
  * @return 当前代次任务，空表示无任务 */
 @Select(value="UPDATE configuration_probe_job SET state='RUNNING',generation=generation+1,attempts=attempts+1,lease_until=clock_timestamp()+interval '1 minute',version=version+1,updated_at=now() WHERE id=(SELECT id FROM configuration_probe_job WHERE state='QUEUED' OR state='RUNNING' AND lease_until<clock_timestamp() ORDER BY created_at,id FOR UPDATE SKIP LOCKED LIMIT 1) RETURNING id,kind,target_id,target_version,created_by,generation,attempts",affectData=true)
 Row claim();
 /** 当前代次且租约有效时记录终态。
  * @param id 任务
  * @param generation 执行代次
  * @param error 安全错误码，空为成功
  * @return 更新数 */
 @Update("UPDATE configuration_probe_job SET state=CASE WHEN CAST(#{error} AS text) IS NULL THEN 'SUCCEEDED' ELSE 'FAILED' END,error_code=#{error},lease_until=NULL,version=version+1,updated_at=now() WHERE id=#{id} AND generation=#{generation} AND state='RUNNING' AND lease_until>clock_timestamp()")
 int finish(UUID id,long generation,String error);
 /** 当前有效管理员检查。
  * @param actor 账户
  * @return 是否有效 */
 @Select("SELECT EXISTS(SELECT 1 FROM user_account WHERE id=#{actor} AND platform='admin' AND status='ENABLED')") boolean enabled(UUID actor);
 /** 选择当前启用凭证，秘密仍只保留引用。
  * @param model 模型
  * @return 密钥身份和版本 */
 @Select("SELECT id,version,secret_ref FROM model_key WHERE model_id=#{model} AND status='ENABLED' ORDER BY created_at,id LIMIT 1") Key key(UUID model);
 /** 任务执行快照。
  * @param id 任务
  * @param kind 类型
  * @param targetId 配置
  * @param targetVersion 申请时版本
  * @param createdBy 申请者
  * @param generation 租约代次
  * @param attempts 领取次数 */
 record Row(UUID id,String kind,UUID targetId,long targetVersion,UUID createdBy,long generation,int attempts){}
 /** 凭证快照，不含明文。
  * @param id 密钥
  * @param version 版本
  * @param secretRef 加密引用 */
 record Key(UUID id,long version,UUID secretRef){}
}
