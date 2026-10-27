package cn.zhixiang.platform.operations.mapper;

import cn.zhixiang.platform.operations.entity.OutboxEventEntity;

import org.apache.ibatis.annotations.*;

import java.util.*;

/** Outbox 租约持久化；领取原子提交，网络发布不占用数据库事务和连接。 */
@Mapper
public interface OutboxMapper {
    /**
     * 返回最多20条领取事件；过期租约重新领取并递增 fencing generation。
     *
     * @return 最多20条领取事件；过期租约重新领取并递增 fencing generation
     */
    @Select(
            """
WITH candidates AS (
 SELECT id FROM outbox_event WHERE status='PENDING' OR (status='SENDING' AND lease_until<now())
 ORDER BY created_at,id FOR UPDATE SKIP LOCKED LIMIT 20
)
UPDATE outbox_event e SET status='SENDING',lease_until=now()+interval '120 seconds',
generation=generation+1,attempts=attempts+1 FROM candidates c WHERE e.id=c.id
RETURNING e.id,e.aggregate_id,e.event_type,e.payload::text,e.generation
""")
    List<OutboxEventEntity> claim();

    /**
     * 返回标记数量，旧工作者为0。
     *
     * @param id 事件
     * @param generation 领取代数
     * @return 标记数量，旧工作者为0
     */
    @Update(
            "UPDATE outbox_event SET status='SENT',sent_at=now(),lease_until=NULL WHERE id=#{id}"
                    + " AND generation=#{generation} AND status='SENDING'")
    int sent(UUID id, long generation);
}
