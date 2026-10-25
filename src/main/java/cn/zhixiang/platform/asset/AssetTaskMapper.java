package cn.zhixiang.platform.asset;
import java.util.UUID;
import org.apache.ibatis.annotations.*;
/** 数据库任务租约，generation用于隔离迟到的worker回写。 */
@Mapper
public interface AssetTaskMapper {
 /** 领取一个就绪任务或过期租约，单条SQL原子完成。
 * @return 本次任务或null */
 @Select(value="WITH picked AS (SELECT asset_id FROM asset_validation_task WHERE (state='QUEUED' AND next_attempt_at<=clock_timestamp()) OR (state='RUNNING' AND lease_until<clock_timestamp()) ORDER BY next_attempt_at,asset_id FOR UPDATE SKIP LOCKED LIMIT 1) UPDATE asset_validation_task t SET state='RUNNING',version=version+1,attempts=attempts+1,generation=generation+1,lease_until=clock_timestamp()+interval '10 minutes',updated_at=now() FROM picked WHERE t.asset_id=picked.asset_id RETURNING t.asset_id,t.generation,t.attempts",affectData=true) Job claim();
 /** 完成仅属于当前且未过期租约的任务，调用方在短事务内更新资产。
 * @param id 资产
 * @param generation 租约代次
 * @param code 结果代码，可空
 * @return 更新数量 */
 @Update("UPDATE asset_validation_task SET version=version+1,state='DONE',lease_until=NULL,error_code=#{code},updated_at=now() WHERE asset_id=#{id} AND generation=#{generation} AND state='RUNNING' AND lease_until>clock_timestamp()") int finish(UUID id,long generation,String code);
 /** 发布验证后的固定私有对象引用。
 * @param id 资产
 * @param key 私有键
 * @param version 对象版本
 * @return 更新数量，删除后为0 */
 @Update("UPDATE asset SET state='READY',private_key=#{key},object_version=#{version},version=asset.version+1,updated_at=now() WHERE id=#{id} AND deleted_at IS NULL AND state='QUARANTINED'") int ready(UUID id,String key,String version);
 /** 明确拒绝文件。
 * @param id 资产
 * @return 更新数量 */
 @Update("UPDATE asset SET state='REJECTED',version=version+1,updated_at=now() WHERE id=#{id} AND deleted_at IS NULL AND state='QUARANTINED'") int reject(UUID id);
 /** 失败补偿，保留资产隔离；最多八次后留待运维重试。
 * @param id 资产
 * @param generation 租约代次
 * @param code 无秘密的错误码 */
 @Update("UPDATE asset_validation_task SET version=version+1,state=CASE WHEN attempts>=8 THEN 'FAILED' ELSE 'QUEUED' END,lease_until=NULL,next_attempt_at=clock_timestamp()+least(3600,10*power(2,least(attempts,12))::int)*interval '1 second',error_code=#{code},updated_at=now() WHERE asset_id=#{id} AND generation=#{generation} AND state='RUNNING'") void retry(UUID id,long generation,String code);
 /** 本次领取的租约。
 * @param assetId 资产
 * @param generation 代次
 * @param attempts 已执行次数 */
 record Job(UUID assetId,long generation,int attempts) {}
}
