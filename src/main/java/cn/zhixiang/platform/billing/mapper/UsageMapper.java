package cn.zhixiang.platform.billing.mapper;

import cn.zhixiang.platform.billing.dto.UsageQuery;
import cn.zhixiang.platform.billing.dto.UsageResponse;

import org.apache.ibatis.annotations.*;

import java.util.*;

/** 用量只读查询；保留历史账户，不能因会员到期隐藏已经发生的费用。 */
@Mapper
public interface UsageMapper {
    /** 共用归属与筛选，计数和分页必须一致。 */
    String FILTER =
            " FROM quota_hold h JOIN quota_account a ON a.id=h.account_id WHERE <choose><when"
                + " test='admin'>1=1 <if test='student != null'>AND"
                + " a.user_id=#{student}</if></when><otherwise>a.user_id=#{student}</otherwise></choose>"
                + " AND h.run_id::text ILIKE #{q.pattern} ESCAPE E'\\\\' <if test='q.status !="
                + " null'>AND h.state=#{q.status}</if> <if test='q.from != null'>AND h.created_at"
                + " &gt;= #{q.start}</if> <if test='q.to != null'>AND h.created_at &lt;"
                + " #{q.end}</if> ";

    /**
     * 读取本人页，未结算用量保留null，释放金额确定为0。
     *
     * @param q 过滤
     * @param student 服务端学生ID
     * @param admin 服务端选择管理员范围，禁止直接绑定客户端参数
     * @return 当前页
     */
    @Select(
            "<script>SELECT h.run_id AS"
                + " id,h.run_id,h.input_tokens,h.output_tokens,h.cached_tokens,CASE WHEN"
                + " h.state='RELEASED' THEN '0' ELSE h.actual_amount::text END AS amount,(SELECT"
                + " coalesce(sum(CASE WHEN direction='DEBIT' THEN amount ELSE -amount END),0)::text"
                + " FROM billing_adjustment b WHERE b.run_id=h.run_id) AS adjustment_amount,CASE"
                + " WHEN h.state='SETTLED' THEN (h.actual_amount+(SELECT coalesce(sum(CASE WHEN"
                + " direction='DEBIT' THEN amount ELSE -amount END),0) FROM billing_adjustment b"
                + " WHERE b.run_id=h.run_id))::text WHEN h.state='RELEASED' THEN '0' ELSE NULL END"
                + " AS net_amount,'CNY' AS currency,h.price_id AS"
                + " price_version_id,h.state,h.created_at,CASE WHEN EXISTS(SELECT 1 FROM"
                + " usage_adjudication u WHERE u.run_id=h.run_id AND u.state='APPROVED') THEN"
                + " 'MANUAL_ADJUDICATION' WHEN EXISTS(SELECT 1 FROM run_usage_evidence e WHERE"
                + " e.run_id=h.run_id) THEN 'ORIGINAL_CALL' ELSE 'UNCONFIRMED' END AS"
                + " evidence_source"
                    + FILTER
                    + " ORDER BY ${q.orderBy} LIMIT #{q.size} OFFSET #{q.offset}</script>")
    List<UsageResponse> list(@Param("q") UsageQuery q, UUID student, boolean admin);

    /**
     * 读取相同权限范围的总数。
     *
     * @param q 过滤
     * @param student 当前学生
     * @param admin 服务端范围标志
     * @return 总数
     */
    @Select("<script>SELECT count(*)" + FILTER + "</script>")
    long count(@Param("q") UsageQuery q, UUID student, boolean admin);
}
