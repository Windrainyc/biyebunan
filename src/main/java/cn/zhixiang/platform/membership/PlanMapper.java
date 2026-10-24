package cn.zhixiang.platform.membership;
import org.apache.ibatis.annotations.*;
import cn.zhixiang.platform.shared.PageQuery;
import java.util.*;
import java.time.OffsetDateTime;
import java.math.BigDecimal;
/** 套餐存储。排序仅使用PageQuery白名单；权益版本不可UPDATE。 */
@Mapper
public interface PlanMapper {
 /** 返回套餐元数据。
 * @param q 分页筛选
  * @return 套餐元数据 */
 @Select("<script>SELECT * FROM membership_plan WHERE (name ILIKE #{q.pattern} OR code ILIKE #{q.pattern}) <if test='q.status != null'>AND status=#{q.status}</if> ORDER BY ${q.orderBy} LIMIT #{q.size} OFFSET #{q.offset}</script>") List<Row> list(@Param("q") PageQuery q);
 /** 返回总数。
 * @param q 筛选
  * @return 总数 */
 @Select("<script>SELECT count(*) FROM membership_plan WHERE (name ILIKE #{q.pattern} OR code ILIKE #{q.pattern}) <if test='q.status != null'>AND status=#{q.status}</if></script>") long count(@Param("q") PageQuery q);
 /** 返回元数据或null。
 * @param id 套餐
  * @return 元数据或null */
 @Select("SELECT * FROM membership_plan WHERE id=#{id}") Row get(UUID id);
 /** 返回锁定元数据。
 * @param id 套餐
  * @return 锁定元数据 */
 @Select("SELECT * FROM membership_plan WHERE id=#{id} FOR UPDATE") Row lock(UUID id);
 /** 在调用方业务事务内插入记录，由数据库约束保证唯一性和引用完整性。
 * @param id 新标识
  * @param w 输入
  * @param actor 操作者 */
 @Insert("INSERT INTO membership_plan(id,name,code,status,created_by,updated_by) VALUES(#{id},#{w.name},#{w.code},#{w.status},#{actor},#{actor})") void insert(UUID id,@Param("w") MembershipDtos.PlanWrite w,UUID actor);
 /** 返回变更数。
 * @param id 套餐
  * @param version CAS版本
  * @param name 新名称
  * @param code 新编码
  * @param status 启停
  * @param actor 操作者
  * @return 变更数 */
 @Update("UPDATE membership_plan SET name=#{name},code=#{code},status=#{status},version=version+1,updated_by=#{actor},updated_at=now() WHERE id=#{id} AND version=#{version}") int update(UUID id,long version,String name,String code,String status,UUID actor);
 /** 返回删除数。
 * @param id 套餐；任何会员历史引用都应禁止删除
  * @return 删除数 */
 @Delete("DELETE FROM membership_plan WHERE id=#{id}") int delete(UUID id);
 /** 返回历史引用数。
 * @param id 套餐
  * @return 历史引用数 */
 @Select("SELECT count(*) FROM membership WHERE plan_id=#{id}") long references(UUID id);
 /** 返回当前已生效的不可变权益。
 * @param id 套餐
  * @return 当前已生效的不可变权益 */
 @Select("SELECT * FROM membership_plan_version WHERE plan_id=#{id} AND effective_from<=CURRENT_TIMESTAMP ORDER BY effective_from DESC LIMIT 1") Version current(UUID id);
 /** 在调用方业务事务内插入记录，由数据库约束保证唯一性和引用完整性。
 * @param id 新权益UUID
  * @param plan 套餐
  * @param days 有效天数
  * @param tokens 月词元
  * @param amount 月人民币
  * @param effectiveFrom 生效时间；调用者持套餐锁 */
 @Insert("INSERT INTO membership_plan_version(id,plan_id,version_number,duration_days,monthly_token_quota,monthly_amount_limit,effective_from) SELECT #{id},#{plan},COALESCE(max(version_number),0)+1,#{days},#{tokens},#{amount},COALESCE(#{effectiveFrom},CURRENT_TIMESTAMP) FROM membership_plan_version WHERE plan_id=#{plan}") void append(UUID id,UUID plan,int days,long tokens,BigDecimal amount,OffsetDateTime effectiveFrom);
 /** 返回不可变版本。
 * @param id 权益
  * @return 不可变版本 */
 @Select("SELECT * FROM membership_plan_version WHERE id=#{id}") Version version(UUID id);
 /** 返回权益版本。
 * @param plan 套餐
  * @param q 版本号字面检索/分页
  * @return 权益版本 */
 @Select("<script>SELECT *,created_at AS updated_at FROM membership_plan_version WHERE plan_id=#{plan} AND CAST(version_number AS text) ILIKE #{q.pattern} ORDER BY ${q.orderBy} LIMIT #{q.size} OFFSET #{q.offset}</script>") List<Version> versions(UUID plan,@Param("q") PageQuery q);
 /** 返回数量。
 * @param plan 套餐
  * @param q 检索
  * @return 数量 */
 @Select("SELECT count(*) FROM membership_plan_version WHERE plan_id=#{plan} AND CAST(version_number AS text) ILIKE #{q.pattern}") long versionsCount(UUID plan,@Param("q") PageQuery q);
 /** 持久化查询记录，仅供服务层映射和业务判断。
 * @param id 标识
  * @param name 名称
  * @param code 编码
  * @param status 状态
  * @param version CAS
  * @param createdAt 创建
  * @param updatedAt 更新 */
 record Row(UUID id,String name,String code,String status,long version,OffsetDateTime createdAt,OffsetDateTime updatedAt) {}
 /** 套餐权益的数据库不可变版本记录。
 * @param id 权益标识
  * @param planId 套餐
  * @param versionNumber 套餐内序号
  * @param durationDays 天数
  * @param monthlyTokenQuota 月词元
  * @param monthlyAmountLimit 月金额，精度六位
  * @param effectiveFrom 生效
  * @param createdAt 追加 */
 record Version(UUID id,UUID planId,long versionNumber,int durationDays,long monthlyTokenQuota,BigDecimal monthlyAmountLimit,OffsetDateTime effectiveFrom,OffsetDateTime createdAt) {}
}
