package cn.zhixiang.platform.exporting;
import java.util.*;
import java.time.*;
import org.apache.ibatis.annotations.*;
/** 导出任务持久化与租约操作；对象IO在事务外。 */
@Mapper
public interface ExportMapper {
 /** 限制同一账户积压与每小时申请数；入口已持账户幂等锁。
  * @param actor 账户
  * @return 是否超过配额 */
 @Select("SELECT count(*) FILTER (WHERE state IN ('QUEUED','RUNNING'))>=3 OR count(*) FILTER (WHERE created_at>clock_timestamp()-interval '1 hour')>=20 FROM export_job WHERE created_by=#{actor}")
 boolean saturated(UUID actor);
 /** 新增任务。
  * @param id 任务
  * @param actor 申请者
  * @param platform 服务端平台
  * @param w 参数
  * @param storage 配置
  */
 @Insert("INSERT INTO export_job(id,created_by,platform,kind,from_date,to_date,storage_id) VALUES(#{id},#{actor},#{platform},#{w.kind},#{w.from},#{w.to},#{storage})")
 void insert(UUID id,UUID actor,String platform,ExportDtos.Request w,UUID storage);
 /** 读取任务。
  * @param id 任务
  * @return 记录或null
  */
 @Select("SELECT * FROM export_job WHERE id=#{id}")
 Row get(UUID id);
 /** 锁定任务以避免旧代次覆盖新报告范围。
  * @param id 任务
  * @return 当前记录 */
 @Select("SELECT * FROM export_job WHERE id=#{id} FOR UPDATE")
 Row lock(UUID id);
 /** 领取任务并提高fencing代次。
  * @return 已领取任务或null
  */
 @Select(value="WITH candidate AS (SELECT id FROM export_job WHERE state='QUEUED' OR (state='RUNNING' AND lease_until<clock_timestamp()) ORDER BY created_at,id FOR UPDATE SKIP LOCKED LIMIT 1) UPDATE export_job j SET state='RUNNING',attempts=attempts+1,generation=generation+1,lease_until=clock_timestamp()+interval '5 minutes',version=version+1,updated_at=now(),error_code=NULL FROM candidate c WHERE j.id=c.id RETURNING j.*",affectData=true)
 Row claim();
 /** 保存每次尝试唯一对象键。
  * @param id 任务
  * @param generation 代次
  * @param key 唯一键
  */
 @Insert("INSERT INTO export_attempt(job_id,generation,object_key) VALUES(#{id},#{generation},#{key})")
 void attempt(UUID id,long generation,String key);
 /** 保存报告所含订单，重复则忽略。
  * @param id 任务
  * @param order 订单
  */
 @Insert("INSERT INTO export_order_scope(job_id,order_id) VALUES(#{id},#{order}) ON CONFLICT DO NOTHING")
 void scope(UUID id,UUID order);
 /** 清除旧尝试范围。
  * @param id 任务
  */
 @Delete("DELETE FROM export_order_scope WHERE job_id=#{id}")
 void clearScope(UUID id);
 /** 保存审计额外权限要求。
  * @param id 任务
  * @param chat 私聊元数据标记
  */
 @Update("UPDATE export_job SET includes_chat=#{chat} WHERE id=#{id}")
 void chat(UUID id,boolean chat);
 /** 检测中介订单归属是否全部仍有效。
  * @param id 任务
  * @param actor 中介
  * @return 是否有失效归属
  */
 @Select("SELECT EXISTS(SELECT 1 FROM export_order_scope s JOIN software_order o ON o.id=s.order_id WHERE s.job_id=#{id} AND o.partner_id IS DISTINCT FROM #{actor})")
 boolean scopeChanged(UUID id,UUID actor);
 /** 读取启用账户平台。
  * @param id 用户
  * @return 平台或null
  */
 @Select("SELECT platform FROM user_account WHERE id=#{id} AND status='ENABLED'")
 String platform(UUID id);
 /** 记录对象版本供准确清理。
  * @param id 任务
  * @param generation 代次
  * @param objectVersion 版本
  */
 @Update("UPDATE export_attempt SET object_version=#{objectVersion} WHERE job_id=#{id} AND generation=#{generation}")
 void objectVersion(UUID id,long generation,String objectVersion);
 /** 完成仍有效的租约。
  * @param id 任务
  * @param generation 代次
  * @param key 私有键
  * @param objectVersion 版本
  * @return 命中数量
  */
 @Update("UPDATE export_job SET state='SUCCEEDED',object_key=#{key},object_version=#{objectVersion},result_expires_at=clock_timestamp()+interval '24 hours',lease_until=NULL,version=version+1,updated_at=now() WHERE id=#{id} AND generation=#{generation} AND state='RUNNING' AND lease_until>clock_timestamp()")
 int finish(UUID id,long generation,String key,String objectVersion);
 /** 失败当前尝试，禁止旧worker覆盖新状态。
  * @param id 任务
  * @param generation 代次
  * @param code 稳定错误码
  * @return 命中数量
  */
 @Update("UPDATE export_job SET state='FAILED',error_code=#{code},lease_until=NULL,version=version+1,updated_at=now() WHERE id=#{id} AND generation=#{generation} AND state='RUNNING'")
 int fail(UUID id,long generation,String code);
 /** 持久任务记录。
  * @param id 任务ID
  * @param createdBy 申请者
  * @param platform 申请平台
  * @param kind 导出类型
  * @param fromDate 首日
  * @param toDate 末日
  * @param storageId 私有存储配置
  * @param state 任务状态
  * @param attempts 已尝试次数
  * @param generation 租约代次
  * @param leaseUntil 租约截止
  * @param objectKey 已完成对象键
  * @param objectVersion 对象版本
  * @param resultExpiresAt 下载截止
  * @param errorCode 稳定错误码
  * @param version CAS版本
  * @param createdAt 创建时间
  * @param updatedAt 修改时间
  * @param includesChat 是否包含需额外权限的审计元数据
  */
 record Row(UUID id,UUID createdBy,String platform,String kind,LocalDate fromDate,LocalDate toDate,UUID storageId,String state,int attempts,long generation,OffsetDateTime leaseUntil,String objectKey,String objectVersion,OffsetDateTime resultExpiresAt,String errorCode,long version,OffsetDateTime createdAt,OffsetDateTime updatedAt,boolean includesChat){}
}
