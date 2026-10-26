package cn.zhixiang.platform.billing;
import java.util.UUID;
import org.apache.ibatis.annotations.*;
/** 核对任务持久层；短事务只检查已保存证据，不调用外部模型。 */
@Mapper
public interface ReconciliationMapper {
 /** 查找排队任务，调用方已经锁定该运行。
  * @param run 运行
  * @return 任务ID或null */
 @Select("SELECT id FROM billing_reconciliation_task WHERE run_id=#{run} AND state='QUEUED'") UUID active(UUID run);
 /** 创建任务。
  * @param id 任务
  * @param run 运行
  * @param reason 核对原因
  * @param actor 管理员 */
 @Insert("INSERT INTO billing_reconciliation_task(id,run_id,reason,created_by) VALUES(#{id},#{run},#{reason},#{actor})") void insert(UUID id,UUID run,String reason,UUID actor);
 /** 读取候选，不提前持任务锁，保持账户→运行→任务锁顺序。
  * @return 任务或null */
 @Select("SELECT id,run_id,created_by,state FROM billing_reconciliation_task WHERE state='QUEUED' ORDER BY created_at,id LIMIT 1") Task candidate();
 /** 加锁复查任务。
  * @param id 任务
  * @return 当前任务 */
 @Select("SELECT id,run_id,created_by,state FROM billing_reconciliation_task WHERE id=#{id} FOR UPDATE") Task lock(UUID id);
 /** 记录核对结果。
  * @param id 任务
  * @param state SUCCEEDED或FAILED
  * @param error 稳定错误码，可空 */
 @Update("UPDATE billing_reconciliation_task SET state=#{state},error_code=#{error},version=version+1,updated_at=now() WHERE id=#{id} AND state='QUEUED'") void finish(UUID id,String state,String error);
 /** 内部任务信息。
  * @param id ID
  * @param runId 原运行
  * @param createdBy 原操作管理员
  * @param state 状态 */
 record Task(UUID id,UUID runId,UUID createdBy,String state){}
}
