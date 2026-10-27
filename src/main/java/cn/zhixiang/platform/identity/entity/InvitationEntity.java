package cn.zhixiang.platform.identity.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 持久化查询记录，仅供服务层映射和业务判断。
 *
 * @param id 邀请标识
 * @param userId 目标账户
 * @param expiresAt 过期时间
 * @param consumedAt 消费时间，未消费为空
 */
public record InvitationEntity(
        UUID id, UUID userId, OffsetDateTime expiresAt, OffsetDateTime consumedAt) {}
