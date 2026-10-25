package cn.zhixiang.platform.chat;
import java.util.*;
import java.time.OffsetDateTime;
import org.apache.ibatis.annotations.*;
/** 流事件持久层；先锁运行再分配序号，避免多节点重复和提交顺序颠倒。 */
@Mapper
public interface RunJournalMapper {
 /** 锁定运行并读取当前流状态。
 * @param run 运行
 * @return 记录或null */
 @Select("SELECT id,state,last_sequence FROM generation_run WHERE id=#{run} FOR UPDATE") Head lock(UUID run);
 /** 只读头部，用于快照事务内回放。
 * @param run 运行
 * @return 记录或null */
 @Select("SELECT id,state,last_sequence FROM generation_run WHERE id=#{run}") Head head(UUID run);
 /** 检查是否已有不可重复终态事件；调用方须先锁运行。
 * @param run 运行
 * @return 已有终态事件 */
 @Select("SELECT EXISTS(SELECT 1 FROM run_event WHERE run_id=#{run} AND type IN ('completed','failed','cancelled'))") boolean terminal(UUID run);
 /** 同事务推进序号；更新版本使查询ETag能反映新事件。
 * @param run 已锁运行
 * @return 新序号 */
 @Select(value="UPDATE generation_run SET last_sequence=last_sequence+1,version=version+1,updated_at=now() WHERE id=#{run} RETURNING last_sequence",affectData=true) long next(UUID run);
 /** 保存不可覆盖事件。
 * @param run 运行
 * @param sequence 新序号
 * @param type 事件类型
 * @param payload 已验证JSON对象 */
 @Insert("INSERT INTO run_event(run_id,sequence,type,payload) VALUES(#{run},#{sequence},#{type},CAST(#{payload} AS jsonb))") void insert(UUID run,long sequence,String type,String payload);
 /** 保留窗口内的有序批次。
 * @param run 运行
 * @param after 已收到序号
 * @param limit 有界数量
 * @return 事件 */
 @Select("SELECT run_id,sequence,type,payload::text AS payload,created_at,expires_at FROM run_event WHERE run_id=#{run} AND sequence>#{after} AND expires_at>CURRENT_TIMESTAMP ORDER BY sequence LIMIT #{limit}") List<Event> after(UUID run,long after,int limit);
 /** 运行流头部。
 * @param id 运行ID
 * @param state 业务状态
 * @param lastSequence 已提交最高序号 */
 record Head(UUID id,String state,long lastSequence){}
 /** 内部持久事件，交由SSE用例序列化；不能未经授权直接暴露。
 * @param runId 运行
 * @param sequence 单调序号
 * @param type 事件类型
 * @param payload JSON对象
 * @param createdAt 创建
 * @param expiresAt 回放到期 */
 record Event(UUID runId,long sequence,String type,String payload,OffsetDateTime createdAt,OffsetDateTime expiresAt){}
}
