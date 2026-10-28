package cn.zhixiang.platform.exporting.mapper;

import cn.zhixiang.platform.exporting.entity.ExportDataDataEntity;

import org.apache.ibatis.annotations.*;

import java.time.OffsetDateTime;
import java.util.*;

/** 固定导出字段白名单；最多读取10001行以检测超限，禁止静默截断。 */
@Mapper
public interface ExportDataMapper {
    /**
     * 导出订单元数据，不包含手机号、凭证或私聊。
     *
     * @param start 起点
     * @param end 排他终点
     * @param actor 申请人
     * @param admin 是否管理员
     * @return 行及归属订单
     */
    @Select(
            "SELECT o.id AS"
                + " order_id,jsonb_build_array(o.id,o.order_no,o.title,o.amount,o.status,o.created_at)::text"
                + " AS cells FROM software_order o WHERE (#{admin} OR o.partner_id=#{actor}) AND"
                + " o.created_at>=#{start} AND o.created_at<#{end} ORDER BY o.created_at,o.id LIMIT"
                + " 10001")
    List<ExportDataDataEntity> orders(
            OffsetDateTime start, OffsetDateTime end, UUID actor, boolean admin);

    /**
     * 导出项目进度，排除需求正文和文件地址。
     *
     * @param start 起点
     * @param end 排他终点
     * @param actor 申请者
     * @param admin 管理员
     * @return 行及归属订单
     */
    @Select(
            "SELECT o.id AS"
                + " order_id,jsonb_build_array(p.id,o.order_no,p.title,p.stage,p.delivery_date,p.created_at)::text"
                + " AS cells FROM software_project p JOIN software_order o ON o.id=p.order_id WHERE"
                + " (#{admin} OR o.partner_id=#{actor}) AND p.created_at>=#{start} AND"
                + " p.created_at<#{end} ORDER BY p.created_at,p.id LIMIT 10001")
    List<ExportDataDataEntity> projects(
            OffsetDateTime start, OffsetDateTime end, UUID actor, boolean admin);

    /**
     * 导出运行账务；未知金额保持空，不替代零值。
     *
     * @param start 预占创建时间起点
     * @param end 排他终点
     * @return 无私聊文本的账务行
     */
    @Select(
            "SELECT NULL::uuid AS"
                + " order_id,jsonb_build_array(h.run_id,a.user_id,h.state,h.input_tokens,h.output_tokens,h.actual_amount,h.adjustment_amount,h.created_at,CASE"
                + " h.evidence_source WHEN 'CORRECTED_ORIGINAL_CALL' THEN '原调用双人更正' WHEN"
                + " 'MANUAL_ADJUDICATION' THEN '人工裁决' WHEN 'ORIGINAL_CALL' THEN '原调用返回' ELSE '未确认'"
                + " END,h.effective_input_tokens,h.effective_output_tokens,h.effective_cached_tokens,h.correction_amount,h.net_amount)::text"
                + " AS cells FROM usage_billing_projection h JOIN quota_account a ON"
                + " a.id=h.account_id WHERE h.created_at>=#{start} AND h.created_at<#{end} ORDER BY"
                + " h.created_at,h.run_id LIMIT 10001")
    List<ExportDataDataEntity> usage(OffsetDateTime start, OffsetDateTime end);

    /**
     * 导出安全审计元数据，私聊资源另行过滤。
     *
     * @param start 起点
     * @param end 排他终点
     * @param chat 是否有私聊审计权限
     * @return 不含detail原文的行
     */
    @Select(
            "SELECT NULL::uuid AS"
                + " order_id,jsonb_build_array(id,actor_id,action,resource_type,resource_id,detail->>'reason',created_at)::text"
                + " AS cells FROM audit_event WHERE (#{chat} OR resource_type NOT IN"
                + " ('conversation','conversation_catalog','generation_run','chat_message')) AND"
                + " created_at>=#{start} AND created_at<#{end} ORDER BY created_at,id LIMIT 10001")
    List<ExportDataDataEntity> audits(OffsetDateTime start, OffsetDateTime end, boolean chat);
}
