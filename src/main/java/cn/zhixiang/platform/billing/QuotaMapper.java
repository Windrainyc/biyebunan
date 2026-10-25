package cn.zhixiang.platform.billing;
import java.util.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.apache.ibatis.annotations.*;
/** 额度持久层，锁顺序为学生账户→额度账户→预占；结算不需要重新锁学生。 */
@Mapper
public interface QuotaMapper {
 /** 锁定启用学生，串行会员变更和首次额度授予。
 * @param user 学生
 * @return 学生或null */
 @Select("SELECT id FROM user_account WHERE id=#{user} AND platform='student' AND status='ENABLED' FOR UPDATE") UUID user(UUID user);
 /** 验证预占所属运行、学生和价格，运行身份由数据库不可变触发器保护。
 * @param run 运行
 * @param user 学生
 * @param price 价格
 * @return 是否一致 */
 @Select("SELECT EXISTS(SELECT 1 FROM generation_run WHERE id=#{run} AND student_id=#{user} AND price_id=#{price})") boolean runMatches(UUID run,UUID user,UUID price);
 /** 当前有效权益快照。
 * @param user 学生
 * @return 权益或null */
 @Select("SELECT m.id,v.monthly_token_quota AS tokens,v.monthly_amount_limit AS amount,m.expires_at FROM membership m JOIN membership_plan_version v ON v.id=m.plan_version_id WHERE m.user_id=#{user} AND m.revoked_at IS NULL AND m.starts_at<=clock_timestamp() AND m.expires_at>clock_timestamp() ORDER BY m.starts_at DESC LIMIT 1") Benefit benefit(UUID user);
 /** 幂等初始化UTC自然月账户，使用事务起点避免创建与查询横跨月界。
 * @param id 候选账户
 * @param user 学生 */
 @Insert("INSERT INTO quota_account(id,user_id,period_start,period_end) VALUES(#{id},#{user},date_trunc('month',transaction_timestamp() AT TIME ZONE 'UTC') AT TIME ZONE 'UTC',(date_trunc('month',transaction_timestamp() AT TIME ZONE 'UTC')+interval '1 month') AT TIME ZONE 'UTC') ON CONFLICT(user_id,period_start) DO NOTHING") void ensure(UUID id,UUID user);
 /** 当前月账户锁。
 * @param user 学生
 * @return 账户 */
 @Select("SELECT * FROM quota_account WHERE user_id=#{user} AND period_start=date_trunc('month',transaction_timestamp() AT TIME ZONE 'UTC') AT TIME ZONE 'UTC' FOR UPDATE") Account current(UUID user);
 /** 锁任意历史账户用于迟到结算，不把费用记入新月份。
 * @param id 账户
 * @return 账户 */
 @Select("SELECT * FROM quota_account WHERE id=#{id} FOR UPDATE") Account lock(UUID id);
 /** 应用同事务账本增量。
 * @param id 账户
 * @param gt 授予词元增量
 * @param rt 冻结词元增量
 * @param st 已用词元增量
 * @param ga 授予金额增量
 * @param ra 冻结金额增量
 * @param sa 已用金额增量 */
 @Update("UPDATE quota_account SET granted_tokens=granted_tokens+#{gt},reserved_tokens=reserved_tokens+#{rt},spent_tokens=spent_tokens+#{st},granted_amount=granted_amount+#{ga},reserved_amount=reserved_amount+#{ra},spent_amount=spent_amount+#{sa},version=version+1,updated_at=now() WHERE id=#{id}") void apply(UUID id,long gt,long rt,long st,BigDecimal ga,BigDecimal ra,BigDecimal sa);
 /** 追加不可覆盖账本。
 * @param id 流水
 * @param account 账户
 * @param source 会员或运行ID
 * @param kind 类型
 * @param gt 授予词元
 * @param rt 冻结词元
 * @param st 消费词元
 * @param ga 授予金额
 * @param ra 冻结金额
 * @param sa 消费金额 */
 @Insert("INSERT INTO quota_ledger(id,account_id,source_id,kind,grant_tokens,reserve_tokens,spend_tokens,grant_amount,reserve_amount,spend_amount) VALUES(#{id},#{account},#{source},#{kind},#{gt},#{rt},#{st},#{ga},#{ra},#{sa})") void ledger(UUID id,UUID account,UUID source,String kind,long gt,long rt,long st,BigDecimal ga,BigDecimal ra,BigDecimal sa);
 /** 运行所属学生，避免跨账户锁造成死锁。
 * @param run 运行
 * @return 学生或null */
 @Select("SELECT a.user_id FROM quota_hold h JOIN quota_account a ON a.id=h.account_id WHERE h.run_id=#{run}") UUID owner(UUID run);
 /** 当前预占。
 * @param run 运行
 * @return 预占或null */
 @Select("SELECT * FROM quota_hold WHERE run_id=#{run}") Hold hold(UUID run);
 /** 账户锁之后锁预占。
 * @param run 运行
 * @return 预占 */
 @Select("SELECT * FROM quota_hold WHERE run_id=#{run} FOR UPDATE") Hold lockHold(UUID run);
 /** 创建唯一预占。
 * @param run 运行
 * @param account 账户
 * @param price 价格快照
 * @param tokens 总词元
 * @param amount 金额 */
 @Insert("INSERT INTO quota_hold(run_id,account_id,price_id,tokens,amount) VALUES(#{run},#{account},#{price},#{tokens},#{amount})") void reserve(UUID run,UUID account,UUID price,long tokens,BigDecimal amount);
 /** 更新预占状态和实际用量。
 * @param run 运行
 * @param state 状态
 * @param input 输入词元，可空
 * @param output 输出词元，可空
 * @param cached 缓存词元，可空
 * @param amount 实际金额，可空 */
 @Update("UPDATE quota_hold SET state=#{state},input_tokens=#{input},output_tokens=#{output},cached_tokens=#{cached},actual_amount=#{amount},updated_at=now() WHERE run_id=#{run}") void state(UUID run,String state,Long input,Long output,Long cached,BigDecimal amount);
 /** 权益快照。
 * @param id 会员
 * @param tokens 月词元额度
 * @param amount 月金额额度
 * @param expiresAt 会员到期 */
 record Benefit(UUID id,long tokens,BigDecimal amount,OffsetDateTime expiresAt){}
 /** 内部额度账户。
 * @param id ID
 * @param userId 用户
 * @param periodStart UTC月起点
 * @param periodEnd UTC月终点
 * @param grantedTokens 已授予
 * @param reservedTokens 已冻结
 * @param spentTokens 已使用
 * @param grantedAmount 已授予金额
 * @param reservedAmount 冻结金额
 * @param spentAmount 已用金额
 * @param version 版本
 * @param createdAt 创建
 * @param updatedAt 更新 */
 record Account(UUID id,UUID userId,OffsetDateTime periodStart,OffsetDateTime periodEnd,long grantedTokens,long reservedTokens,long spentTokens,BigDecimal grantedAmount,BigDecimal reservedAmount,BigDecimal spentAmount,long version,OffsetDateTime createdAt,OffsetDateTime updatedAt){}
 /** 内部预占。
 * @param runId 运行
 * @param accountId 账户
 * @param priceId 价格
 * @param tokens 预占词元
 * @param amount 预占金额
 * @param state 状态
 * @param inputTokens 实际输入
 * @param outputTokens 实际输出
 * @param cachedTokens 实际缓存
 * @param actualAmount 实际金额
 * @param createdAt 创建
 * @param updatedAt 更新 */
 record Hold(UUID runId,UUID accountId,UUID priceId,long tokens,BigDecimal amount,String state,Long inputTokens,Long outputTokens,Long cachedTokens,BigDecimal actualAmount,OffsetDateTime createdAt,OffsetDateTime updatedAt){}
}
