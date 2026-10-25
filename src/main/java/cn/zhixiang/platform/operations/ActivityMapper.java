package cn.zhixiang.platform.operations;
import java.util.UUID;
import org.apache.ibatis.annotations.*;
/** 本地outbox事件真实性核对与统计投影的幂等提交。 */
@Mapper
public interface ActivityMapper {
 /** 核对事件与本库不可变outbox一致，拒绝伪造或错误环境投递。
 * @param id 事件
 * @param aggregate 聚合
 * @param type 事件类型
 * @param data JSON数据
 * @return 是否匹配 */
 @Select("SELECT EXISTS(SELECT 1 FROM outbox_event WHERE id=#{id} AND aggregate_id=#{aggregate} AND event_type=#{type} AND payload=CAST(#{data} AS jsonb))") boolean matches(UUID id,UUID aggregate,String type,String data);
 /** 领取一次消费资格，同事务更新投影；冲突等待另一事务提交后返回0。
 * @param id 事件
 * @param hash 消息摘要
 * @return 1首次，0重复 */
 @Insert("INSERT INTO inbox_event(event_id,consumer,body_sha256) VALUES(#{id},'activity-v1',#{hash}) ON CONFLICT DO NOTHING") int insert(UUID id,String hash);
 /** 已处理摘要。
 * @param id 事件
 * @return 摘要 */
 @Select("SELECT body_sha256 FROM inbox_event WHERE event_id=#{id} AND consumer='activity-v1'") String digest(UUID id);
 /** 按事件产生的UTC日期聚合，不按重投时间计入新一天。
 * @param id 已通过核对的事件 */
 @Insert("INSERT INTO event_activity_day(day,event_type,event_count) SELECT (created_at AT TIME ZONE 'UTC')::date,event_type,1 FROM outbox_event WHERE id=#{id} ON CONFLICT(day,event_type) DO UPDATE SET event_count=event_activity_day.event_count+1") void count(UUID id);
}
