package cn.zhixiang.platform.operations;
import java.util.*;
import java.time.OffsetDateTime;
import org.apache.ibatis.annotations.*;
/** 运维只读投影及资产校验恢复，不将不同任务强制为同一种副作用。 */
@Mapper
public interface JobMapper {
 /** 搜索过滤。 */
 String F=" FROM operational_job WHERE (kind ILIKE #{q.pattern} OR id::text ILIKE #{q.pattern} OR coalesce(error_code,'') ILIKE #{q.pattern}) <if test='q.status != null'>AND state=#{q.status}</if>";
 /** 分页。
 * @param q 条件
 * @return 内部记录 */
 @Select("<script>SELECT *"+F+" ORDER BY ${q.orderBy} LIMIT #{q.size} OFFSET #{q.offset}</script>") List<Row> list(@Param("q") JobDtos.Query q);
 /** 计数。
 * @param q 条件
 * @return 总数 */
 @Select("<script>SELECT count(*)"+F+"</script>") long count(@Param("q") JobDtos.Query q);
 /** 读取任务。
 * @param id ID
 * @return 内部记录 */
 @Select("SELECT * FROM operational_job WHERE id=#{id}") Row get(UUID id);
 /** 先锁任务，与worker完成的锁序一致。
 * @param id ID
 * @return 是否找到 */
 @Select("SELECT asset_id FROM asset_validation_task WHERE asset_id=#{id} FOR UPDATE") UUID lock(UUID id);
 /** 重置失败任务的本轮重试次数；递增generation隔离旧worker。
 * @param id ID
 * @param version CAS版本
 * @return 修改数量 */
 @Update("UPDATE asset_validation_task t SET state='QUEUED',attempts=0,generation=generation+1,version=version+1,lease_until=NULL,next_attempt_at=clock_timestamp(),error_code=NULL,updated_at=now() WHERE asset_id=#{id} AND version=#{version} AND state='FAILED' AND EXISTS(SELECT 1 FROM asset a WHERE a.id=t.asset_id AND a.state='QUARANTINED' AND a.deleted_at IS NULL)") int retry(UUID id,long version);
 /** 记录恢复原因，不持久化异常全文。
 * @param id 事件ID
 * @param job 任务
 * @param actor 操作者
 * @param reason 原因
 * @param error 之前错误 */
 @Insert("INSERT INTO job_retry_event(id,job_id,actor_id,reason,previous_error) VALUES(#{id},#{job},#{actor},#{reason},#{error})") void event(UUID id,UUID job,UUID actor,String reason,String error);
 /** 数据库投影。
 * @param id ID
 * @param version 版本
 * @param createdAt 创建时间
 * @param updatedAt 修改时间
 * @param kind 类别
 * @param state 状态
 * @param progress 百分比
 * @param resultAssetId 结果资产
 * @param errorCode 错误码 */
 record Row(UUID id,long version,OffsetDateTime createdAt,OffsetDateTime updatedAt,String kind,String state,long progress,UUID resultAssetId,String errorCode){}
}
