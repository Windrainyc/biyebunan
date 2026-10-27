package cn.zhixiang.platform.order.mapper;

import org.apache.ibatis.annotations.*;

import java.util.UUID;

/** 取消补偿持久层；调用者先取得订单行锁，所有写入与订单状态变更同事务。 */
@Mapper
public interface OrderCancellationMapper {
    /**
     * 检查未结束调用及未决账务，已开始付费请求不能因取消订单而释放。
     *
     * @param order 订单标识
     * @return 是否存在必须先处理的运行或预占
     */
    @Select(
            "SELECT EXISTS(SELECT 1 FROM generation_run r JOIN chat_conversation c ON"
                + " c.id=r.conversation_id JOIN software_project p ON p.id=c.project_id LEFT JOIN"
                + " quota_hold h ON h.run_id=r.id WHERE p.order_id=#{order} AND (r.state IN"
                + " ('RESERVED','RUNNING') OR h.state IN ('RESERVED','RECONCILING')))")
    boolean unsettled(UUID order);

    /**
     * 暂停未完成项目并记录阶段历史；项目锁在订单锁之后获取。
     *
     * @param order 订单标识
     * @param actor 操作者
     * @param reason 取消原因
     * @return 新增阶段历史数量
     */
    @Insert(
            "WITH old AS (SELECT id,stage FROM software_project WHERE order_id=#{order} AND stage"
                + " NOT IN ('DONE','PAUSED') FOR UPDATE), changed AS (UPDATE software_project p SET"
                + " stage='PAUSED',paused_from=old.stage,version=version+1,updated_at=now(),updated_by=#{actor}"
                + " FROM old WHERE p.id=old.id RETURNING p.id,old.stage) INSERT INTO"
                + " project_stage_event(id,project_id,from_stage,to_stage,reason,actor_id) SELECT"
                + " gen_random_uuid(),id,stage,'PAUSED',#{reason},#{actor} FROM changed")
    int pause(UUID order, UUID actor, String reason);

    /**
     * 失效尚未消费的归属挑战；不改写历史已消费记录。
     *
     * @param order 订单标识
     * @return 失效数量
     */
    @Update(
            "UPDATE order_binding_challenge SET state='FAILED' WHERE order_id=#{order} AND state IN"
                    + " ('PENDING','READY')")
    int invalidate(UUID order);

    /**
     * 保存一次性取消快照，财务标记不声称退款已发生。
     *
     * @param order 订单标识
     * @param previous 取消前状态
     * @param financial 是否已确认收款而需要财务核对
     * @param actor 操作者
     * @param reason 原因
     * @return 写入数量
     */
    @Insert(
            "INSERT INTO"
                + " order_cancellation(order_id,previous_status,financial_review_required,actor_id,reason)"
                + " VALUES(#{order},#{previous},#{financial},#{actor},#{reason})")
    int record(UUID order, String previous, boolean financial, UUID actor, String reason);
}
