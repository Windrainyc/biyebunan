package cn.zhixiang.platform.identity.entity;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * 内部账户投影，不得作为 HTTP 返回值。
 *
 * @param id 标识
 * @param version 版本
 * @param platform 平台
 * @param displayName 昵称
 * @param phoneMasked 脱敏电话
 * @param passwordHash 密码摘要
 * @param status 状态
 * @param authVersion 权限版本
 * @param schoolId 院校
 * @param majorId 专业
 * @param mfaSecretRef MFA密钥引用
 * @param createdAt 创建时间
 * @param avatarIndex 默认头像序号
 * @param avatarAssetId 本人头像
 * @param updatedAt 更新时间
 */
public record AuthAccountEntity(
        UUID id,
        long version,
        String platform,
        String displayName,
        String phoneMasked,
        String passwordHash,
        String status,
        long authVersion,
        UUID schoolId,
        UUID majorId,
        String mfaSecretRef,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        int avatarIndex,
        UUID avatarAssetId) {}
