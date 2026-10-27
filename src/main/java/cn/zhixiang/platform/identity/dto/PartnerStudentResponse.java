package cn.zhixiang.platform.identity.dto;

import org.springframework.transaction.annotation.*;

import java.util.UUID;

/**
 * 中介可见学生摘要。
 *
 * @param id 学生ID
 * @param displayName 昵称
 * @param phoneMasked 脱敏手机号
 * @param schoolName 个人资料院校名，未设置时null
 * @param activeOrderCount 本中介下CONFIRMED/ACTIVE订单数
 */
public record PartnerStudentResponse(
        UUID id,
        String displayName,
        String phoneMasked,
        String schoolName,
        long activeOrderCount) {}
