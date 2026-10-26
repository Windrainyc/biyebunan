package cn.zhixiang.platform.billing;
import java.util.UUID;
import java.math.BigDecimal;
import org.apache.ibatis.annotations.*;
/** 追加调整访问层，调用者必须先锁额度账户和预占。 */
@Mapper
public interface AdjustmentMapper {
 /** 运行累计已调整净额，DEBIT为正。
  * @param run 运行
  * @return 净额 */
 @Select("SELECT coalesce(sum(CASE WHEN direction='DEBIT' THEN amount ELSE -amount END),0) FROM billing_adjustment WHERE run_id=#{run}") BigDecimal net(UUID run);
 /** 追加不可变调整单。
  * @param id 新ID
  * @param account 所属账户
  * @param w 已验证输入
  * @param actor 管理员 */
 @Insert("INSERT INTO billing_adjustment(id,run_id,account_id,direction,amount,reason,evidence_ref,created_by) VALUES(#{id},#{w.runId},#{account},#{w.direction},CAST(#{w.amount} AS numeric),#{w.reason},#{w.evidenceRef},#{actor})") void insert(UUID id,UUID account,@Param("w") AdjustmentDtos.Write w,UUID actor);
 /** 返回已提交事务内的调整单。
  * @param id 调整ID
  * @return 只读视图 */
 @Select("SELECT id,run_id,direction,amount::text AS amount,'CNY' AS currency,reason,evidence_ref,created_by,created_at FROM billing_adjustment WHERE id=#{id}") AdjustmentDtos.View get(UUID id);
}
