package cn.zhixiang.platform.chat;
import java.util.UUID;
import org.apache.ibatis.annotations.*;
/** 付费调用租约与fencing；RUNNING永不回到可调用队列。 */
@Mapper
public interface RunLeaseMapper {
 /** 领取一个尚未开始供应商调用的运行，失效的准备租约允许重领。
 * @param owner 当前进程随机标识
 * @return 运行与代次，队列为空时null */
 @Select(value="WITH candidate AS (SELECT id FROM generation_run WHERE state='RESERVED' AND EXISTS(SELECT 1 FROM quota_hold h WHERE h.run_id=generation_run.id AND h.state='RESERVED') AND (lease_until IS NULL OR lease_until<clock_timestamp()) ORDER BY created_at,id FOR UPDATE SKIP LOCKED LIMIT 1) UPDATE generation_run r SET lease_owner=#{owner},lease_until=clock_timestamp()+interval '30 seconds',generation=generation+1,version=version+1,updated_at=now() FROM candidate c WHERE r.id=c.id RETURNING r.id,r.generation",affectData=true)
 Lease claim(UUID owner);
 /** 持久化调用起点。调用者只有拿到true才可发HTTP；提交后崩溃宁可待核对也不能盲重试。
 * @param run 运行
 * @param owner 租约持有者
 * @param generation fencing代次
 * @return CAS更新数 */
 @Update("UPDATE generation_run SET state='RUNNING',call_started_at=clock_timestamp(),lease_until=clock_timestamp()+interval '4 minutes',version=version+1,updated_at=now() WHERE id=#{run} AND state='RESERVED' AND lease_owner=#{owner} AND generation=#{generation} AND lease_until>clock_timestamp() AND EXISTS(SELECT 1 FROM quota_hold h WHERE h.run_id=generation_run.id AND h.state='RESERVED')") int start(UUID run,UUID owner,long generation);
 /** 读取过期调用候选；核对流程必须按额度账户→运行加锁并重查，不得直接重新派发。
 * @return 一个待核对ID或null */
 @Select("SELECT id FROM generation_run WHERE state='RUNNING' AND lease_until<clock_timestamp() ORDER BY lease_until,id LIMIT 1") UUID expiredCall();
 /** 加锁后复核调用是否失联。
 * @param run 运行
 * @return RUNNING且租约过期 */
 @Select("SELECT EXISTS(SELECT 1 FROM generation_run WHERE id=#{run} AND state='RUNNING' AND lease_until<clock_timestamp())") boolean expired(UUID run);
 /** 在运行锁内验证完成结果仍属于原领取者；允许租约过期后的迟到结果，但拒绝新代次覆盖。
 * @param run 运行
 * @param owner 原领取者
 * @param generation 代次
 * @return 是否匹配 */
 @Select("SELECT EXISTS(SELECT 1 FROM generation_run WHERE id=#{run} AND lease_owner=#{owner} AND generation=#{generation})") boolean owner(UUID run,UUID owner,long generation);
 /** 在运行锁内保存终态；调用者同时提交账务和终态事件。
 * @param run 运行
 * @param state SUCCEEDED/FAILED
 * @param errorCode 稳定错误码或null
 * @param providerId 供应商追踪ID或null */
 @Update("UPDATE generation_run SET state=#{state},error_code=#{errorCode},provider_request_id=#{providerId},version=version+1,updated_at=now() WHERE id=#{run}") void terminal(UUID run,String state,String errorCode,String providerId);
 /** 领取结果，不包含正文或供应商密钥。
 * @param id 运行
 * @param generation fencing代次 */
 record Lease(UUID id,long generation){}
}
