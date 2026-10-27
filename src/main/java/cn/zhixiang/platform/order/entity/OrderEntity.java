package cn.zhixiang.platform.order.entity;

import org.apache.ibatis.annotations.*;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * 内部记录，不能直接作为HTTP响应。
 *
 * @param id ID
 * @param version CAS版本
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 * @param orderNo 订单号
 * @param title 标题
 * @param studentId 已绑定学生
 * @param partnerId 中介
 * @param amount 金额
 * @param currency 币种
 * @param status 状态
 * @param receiptStatus 收款状态
 * @param packageCode 套餐
 * @param schoolId 院校
 * @param majorId 专业
 * @param source 来源
 * @param note 业务备注
 */
public record OrderEntity(
        UUID id,
        long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        String orderNo,
        String title,
        UUID studentId,
        UUID partnerId,
        String amount,
        String currency,
        String status,
        String receiptStatus,
        String packageCode,
        UUID schoolId,
        UUID majorId,
        String source,
        String note) {}
