package cn.zhixiang.platform.identity.entity;

import org.apache.ibatis.annotations.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * MFA登记响应，二维码URI包含秘密种子。
 *
 * @param id 登记
 * @param invitationId 邀请
 * @param secretId 加密密钥引用
 * @param expiresAt 截止
 * @param consumedAt 消费时间
 */
public record InvitationEnrollmentEntity(
        UUID id,
        UUID invitationId,
        UUID secretId,
        OffsetDateTime expiresAt,
        OffsetDateTime consumedAt) {}
