package cn.zhixiang.platform.membership;
import org.apache.ibatis.annotations.*;
import cn.zhixiang.platform.shared.PageQuery;
import java.util.*;
import java.time.OffsetDateTime;
/** 会员事实与只读状态投影；账户锁保证授予与撤销的期间不重叠。 */
@Mapper
public interface MemberMapper {
 /** 会员事实的动态时间状态SQL投影，不持久化易过期的状态。 */
 String PROJECTION="SELECT m.*,p.name AS plan_name,CASE WHEN m.revoked_at IS NOT NULL THEN 'REVOKED' WHEN NOT m.expires_at>CURRENT_TIMESTAMP THEN 'EXPIRED' WHEN m.starts_at>CURRENT_TIMESTAMP THEN 'SCHEDULED' ELSE 'ACTIVE' END AS state FROM membership m JOIN membership_plan p ON p.id=m.plan_id";
 /** 返回页。
 * @param user 用户
  * @param q 搜索套餐名称/分页/排序
  * @param state 投影状态
  * @return 页 */
 @Select("<script>SELECT * FROM ("+PROJECTION+") x WHERE user_id=#{user} AND plan_name ILIKE #{q.pattern} <if test='state != null'>AND state=#{state}</if> ORDER BY ${q.orderBy} LIMIT #{q.size} OFFSET #{q.offset}</script>") List<Row> list(UUID user,@Param("q") PageQuery q,String state);
 /** 返回总数。
 * @param user 用户
  * @param q 检索
  * @param state 状态
  * @return 总数 */
 @Select("<script>SELECT count(*) FROM ("+PROJECTION+") x WHERE user_id=#{user} AND plan_name ILIKE #{q.pattern} <if test='state != null'>AND state=#{state}</if></script>") long count(UUID user,@Param("q") PageQuery q,String state);
 /** 返回投影或null。
 * @param id 会员
  * @return 投影或null */
 @Select("SELECT * FROM ("+PROJECTION+") x WHERE id=#{id}") Row get(UUID id);
 /** 返回当前会员或null，尚未开始/已过期/已撤销不算有效。
 * @param user 当前学生
  * @return 当前会员或null，尚未开始/已过期/已撤销不算有效 */
 @Select("SELECT * FROM ("+PROJECTION+") x WHERE user_id=#{user} AND state='ACTIVE' ORDER BY starts_at DESC LIMIT 1") Row active(UUID user);
 /** 返回未撤销且相交的期间数；调用者先锁用户。
 * @param user 用户
  * @param start 起点
  * @param end 终点
  * @return 未撤销且相交的期间数；调用者先锁用户 */
 @Select("SELECT count(*) FROM membership WHERE user_id=#{user} AND revoked_at IS NULL AND starts_at<#{end} AND expires_at>#{start}") long overlap(UUID user,OffsetDateTime start,OffsetDateTime end);
 /** 在调用方业务事务内插入记录，由数据库约束保证唯一性和引用完整性。
 * @param id 新会员
  * @param user 学生
  * @param plan 套餐
  * @param revision 固定权益版本
  * @param start 包含起点
  * @param end 排除终点
  * @param actor 操作者 */
 @Insert("INSERT INTO membership(id,user_id,plan_id,plan_version_id,starts_at,expires_at,created_by) VALUES(#{id},#{user},#{plan},#{revision},#{start},#{end},#{actor})") void insert(UUID id,UUID user,UUID plan,UUID revision,OffsetDateTime start,OffsetDateTime end,UUID actor);
 /** 返回更新数，撤销不删除引用与快照。
 * @param id 会员
  * @param version CAS
  * @return 更新数，撤销不删除引用与快照 */
 @Update("UPDATE membership SET revoked_at=now(),version=version+1,updated_at=now() WHERE id=#{id} AND version=#{version} AND revoked_at IS NULL") int revoke(UUID id,long version);
 /** 持久化查询记录，仅供服务层映射和业务判断。
 * @param id 标识
  * @param version CAS
  * @param createdAt 创建
  * @param updatedAt 更新
  * @param userId 用户
  * @param planId 套餐
  * @param planVersionId 固定权益版本，仅内部使用
  * @param startsAt 起点
  * @param expiresAt 终点
  * @param state 计算状态 */
 record Row(UUID id,long version,OffsetDateTime createdAt,OffsetDateTime updatedAt,UUID userId,UUID planId,UUID planVersionId,OffsetDateTime startsAt,OffsetDateTime expiresAt,String state) {}
}
