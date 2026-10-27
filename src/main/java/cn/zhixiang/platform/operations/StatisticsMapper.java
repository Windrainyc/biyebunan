package cn.zhixiang.platform.operations;
import java.util.List;
import java.time.OffsetDateTime;
import org.apache.ibatis.annotations.*;
import cn.zhixiang.platform.identity.Actor;
/** 固定SQL指标集；身份来自服务端，禁止请求注入聚合列或归属。 */
@Mapper
public interface StatisticsMapper {
 /** 返回数据库事务开始时间。
  * @return 快照事务时间 */
 @Select("SELECT transaction_timestamp()")
 OffsetDateTime snapshotTime();
 /** 订单、收款、项目和交付日统计，按当前订单归属。
  * @param start 北京时间起点
  * @param end 排他终点
  * @param actor 当前身份
  * @return 有数据日期的业务指标 */
 @Select("""
SELECT 'orders_created' AS metric,(count(*))::text AS value,'count' AS unit,(o.created_at AT TIME ZONE 'Asia/Shanghai')::date::text AS bucket FROM software_order o WHERE (#{actor.platform}='admin' OR (#{actor.platform}='partner' AND o.partner_id=#{actor.id}) OR (#{actor.platform}='student' AND o.student_id=#{actor.id})) AND o.created_at>=#{start} AND o.created_at<#{end} GROUP BY bucket
 UNION ALL
SELECT 'orders_face_amount' AS metric,(sum(o.amount))::text AS value,'CNY' AS unit,(o.created_at AT TIME ZONE 'Asia/Shanghai')::date::text AS bucket FROM software_order o WHERE (#{actor.platform}='admin' OR (#{actor.platform}='partner' AND o.partner_id=#{actor.id}) OR (#{actor.platform}='student' AND o.student_id=#{actor.id})) AND o.created_at>=#{start} AND o.created_at<#{end} GROUP BY bucket
 UNION ALL
SELECT 'receipts_confirmed_amount' AS metric,(sum(r.amount))::text AS value,'CNY' AS unit,(r.confirmed_at AT TIME ZONE 'Asia/Shanghai')::date::text AS bucket FROM order_receipt r JOIN software_order o ON o.id=r.order_id WHERE (#{actor.platform}='admin' OR (#{actor.platform}='partner' AND o.partner_id=#{actor.id}) OR (#{actor.platform}='student' AND o.student_id=#{actor.id})) AND r.confirmed_at>=#{start} AND r.confirmed_at<#{end} GROUP BY bucket
 UNION ALL
SELECT 'projects_created' AS metric,(count(*))::text AS value,'count' AS unit,(p.created_at AT TIME ZONE 'Asia/Shanghai')::date::text AS bucket FROM software_project p JOIN software_order o ON o.id=p.order_id WHERE (#{actor.platform}='admin' OR (#{actor.platform}='partner' AND o.partner_id=#{actor.id}) OR (#{actor.platform}='student' AND o.student_id=#{actor.id})) AND p.created_at>=#{start} AND p.created_at<#{end} GROUP BY bucket
 UNION ALL
SELECT 'delivery_publications' AS metric,(count(*))::text AS value,'count' AS unit,(e.created_at AT TIME ZONE 'Asia/Shanghai')::date::text AS bucket FROM delivery_event e JOIN project_delivery d ON d.id=e.delivery_id JOIN software_project p ON p.id=d.project_id JOIN software_order o ON o.id=p.order_id WHERE (#{actor.platform}='admin' OR (#{actor.platform}='partner' AND o.partner_id=#{actor.id}) OR (#{actor.platform}='student' AND o.student_id=#{actor.id})) AND e.created_at>=#{start} AND e.created_at<#{end} AND e.action='PUBLISHED' GROUP BY bucket
 """)
 List<StatisticsService.Item> business(OffsetDateTime start,OffsetDateTime end,Actor actor);
 /** 从追加账本聚合已结算成本与人工调整，未知冻结单独计数。
  * @param start 起点
  * @param end 排他终点
  * @param actor 管理员或本人学生
  * @return 财务指标，不含私聊内容 */
 @Select("""
 SELECT CASE l.kind WHEN 'SETTLE' THEN 'model_settled_amount' ELSE 'model_adjustment_amount' END AS metric,sum(l.spend_amount)::text AS value,'CNY' AS unit,(l.created_at AT TIME ZONE 'Asia/Shanghai')::date::text AS bucket
 FROM quota_ledger l JOIN quota_account a ON a.id=l.account_id WHERE l.kind IN ('SETTLE','ADJUSTMENT') AND (#{actor.platform}='admin' OR a.user_id=#{actor.id}) AND l.created_at>=#{start} AND l.created_at<#{end} GROUP BY l.kind,bucket
 UNION ALL
 SELECT 'model_settled_tokens',sum(l.spend_tokens)::text,'token',(l.created_at AT TIME ZONE 'Asia/Shanghai')::date::text AS bucket
 FROM quota_ledger l JOIN quota_account a ON a.id=l.account_id WHERE l.kind='SETTLE' AND (#{actor.platform}='admin' OR a.user_id=#{actor.id}) AND l.created_at>=#{start} AND l.created_at<#{end} GROUP BY bucket
 UNION ALL
 SELECT 'model_unresolved_runs',count(*)::text,'count',(h.created_at AT TIME ZONE 'Asia/Shanghai')::date::text AS bucket
 FROM quota_hold h JOIN quota_account a ON a.id=h.account_id WHERE h.state='RECONCILING' AND (#{actor.platform}='admin' OR a.user_id=#{actor.id}) AND h.created_at>=#{start} AND h.created_at<#{end} GROUP BY bucket
 """)
 List<StatisticsService.Item> usage(OffsetDateTime start,OffsetDateTime end,Actor actor);
 /** 按创建帖子、评论或模型运行定义活跃学生；同一人同日去重。
  * @param start 起点
  * @param end 排他终点
  * @param actor 管理员或本人学生
  * @return 活跃学生日数量 */
 @Select("""
 SELECT 'active_students' AS metric,count(DISTINCT e.actor_id)::text AS value,'count' AS unit,(e.created_at AT TIME ZONE 'Asia/Shanghai')::date::text AS bucket
 FROM audit_event e JOIN user_account u ON u.id=e.actor_id WHERE u.platform='student' AND e.action='CREATED' AND e.resource_type IN ('forum_post','forum_comment','generation_run') AND (#{actor.platform}='admin' OR e.actor_id=#{actor.id}) AND e.created_at>=#{start} AND e.created_at<#{end} GROUP BY bucket
 """)
 List<StatisticsService.Item> activeStudents(OffsetDateTime start,OffsetDateTime end,Actor actor);
}
