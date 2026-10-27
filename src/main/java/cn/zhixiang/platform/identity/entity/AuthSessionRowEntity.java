package cn.zhixiang.platform.identity.entity;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * 内部会话行；摘要不对外公开。
 *
 * @param id 会话
 * @param userId 用户
 * @param accessDigest 访问摘要
 * @param refreshDigest 刷新摘要
 * @param csrfDigest CSRF摘要
 * @param authVersion 权限版本
 * @param expiresAt 访问过期
 * @param refreshExpiresAt 刷新过期
 * @param familyId 令牌族
 * @param consumedAt 已轮换时间
 * @param revokedAt 撤销时间
 */
public record AuthSessionRowEntity(
        UUID id,
        UUID userId,
        String accessDigest,
        String refreshDigest,
        String csrfDigest,
        long authVersion,
        OffsetDateTime expiresAt,
        OffsetDateTime refreshExpiresAt,
        UUID familyId,
        OffsetDateTime consumedAt,
        OffsetDateTime revokedAt) {}
