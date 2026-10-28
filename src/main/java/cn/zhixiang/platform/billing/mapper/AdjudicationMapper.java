package cn.zhixiang.platform.billing.mapper;

import cn.zhixiang.platform.billing.dto.AdjudicationDtos;

import org.apache.ibatis.annotations.*;

import java.util.*;

/** 人工裁决持久层；账户与运行锁由服务先取得，凭据创建后不可修改。 */
@Mapper
public interface AdjudicationMapper {
    /** 查询投影，标明人工来源。 */
    String SELECT =
            "SELECT u.*, 'MANUAL_ADJUDICATION' AS source, (u.state='APPROVED' AND"
                    + " u.kind!='CORRECTION' AND EXISTS(SELECT 1 FROM run_usage_evidence e WHERE"
                    + " e.run_id=u.run_id AND (e.kind!=u.kind OR e.input_tokens IS DISTINCT FROM"
                    + " u.input_tokens OR e.output_tokens IS DISTINCT FROM u.output_tokens OR"
                    + " e.cached_tokens IS DISTINCT FROM u.cached_tokens))) AS disputed FROM"
                    + " usage_adjudication u";

    /**
     * 按ID读取。
     *
     * @param id 裁决单
     * @return 单据或null
     */
    @Select(SELECT + " WHERE id=#{id}")
    AdjudicationDtos.View get(UUID id);

    /**
     * 读取并锁定待审核单。
     *
     * @param id 裁决单
     * @return 锁定单据或null
     */
    @Select(SELECT + " WHERE id=#{id} FOR UPDATE")
    AdjudicationDtos.View lock(UUID id);

    /**
     * 防止相同运行重复提交待审声明。
     *
     * @param run 运行
     * @return 待审核是否存在
     */
    @Select(
            "SELECT EXISTS(SELECT 1 FROM usage_adjudication WHERE run_id=#{run} AND"
                    + " state='PENDING')")
    boolean pending(UUID run);

    /**
     * 是否已有指定类型的批准记录，调用者必须持有账户锁。
     *
     * @param run 运行
     * @param correction true查询更正，false查询原人工裁决
     * @return 是否存在
     */
    @Select(
            "SELECT EXISTS(SELECT 1 FROM usage_adjudication WHERE run_id=#{run} AND"
                    + " state='APPROVED' AND (kind='CORRECTION')=#{correction})")
    boolean approved(UUID run, boolean correction);

    /**
     * 新增待复核证据。
     *
     * @param id 单据ID
     * @param run 运行
     * @param w 已验证声明
     * @param actor 提交人
     */
    @Insert(
            "INSERT INTO"
                + " usage_adjudication(id,run_id,kind,input_tokens,output_tokens,cached_tokens,evidence_ref,evidence_sha256,reason,created_by)"
                + " VALUES(#{id},#{run},#{w.kind},#{w.inputTokens},#{w.outputTokens},#{w.cachedTokens},#{w.evidenceRef},#{w.evidenceSha256},#{w.reason},#{actor})")
    void insert(UUID id, UUID run, @Param("w") AdjudicationDtos.Create w, UUID actor);

    /**
     * CAS结束审核，与账务结算同事务。
     *
     * @param id 单据
     * @param version 期望版本
     * @param state 审核终态
     * @param reason 复核理由
     * @param actor 复核人
     * @return 更新行数
     */
    @Update(
            "UPDATE usage_adjudication SET"
                + " state=#{state},version=version+1,reviewed_by=#{actor},review_reason=#{reason},reviewed_at=now()"
                + " WHERE id=#{id} AND version=#{version} AND state='PENDING'")
    int decide(UUID id, long version, String state, String reason, UUID actor);

    /**
     * 分页查询，固定时间顺序并可筛选运行和状态。
     *
     * @param run 可空运行ID
     * @param state 可空状态
     * @param size 页大小
     * @param offset 已校验偏移
     * @return 单据页
     */
    @Select(
            "<script>"
                    + SELECT
                    + " WHERE 1=1 <if test='run != null'>AND run_id=#{run}</if><if test='state !="
                    + " null'>AND state=#{state}</if> ORDER BY created_at DESC,id LIMIT #{size}"
                    + " OFFSET #{offset}</script>")
    List<AdjudicationDtos.View> list(UUID run, String state, int size, int offset);

    /**
     * 计算同范围总数。
     *
     * @param run 可空运行ID
     * @param state 可空状态
     * @return 总数
     */
    @Select(
            "<script>SELECT count(*) FROM usage_adjudication WHERE 1=1 <if test='run != null'>AND"
                + " run_id=#{run}</if><if test='state != null'>AND state=#{state}</if></script>")
    long count(UUID run, String state);
}
