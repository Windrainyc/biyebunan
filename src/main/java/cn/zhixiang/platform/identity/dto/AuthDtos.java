package cn.zhixiang.platform.identity.dto;

import jakarta.validation.constraints.*;

import java.time.OffsetDateTime;
import java.util.*;

/** 认证传输模型；密码只作为输入，不进入响应、审计或日志。 */
public final class AuthDtos {
    private AuthDtos() {}

    /**
     * 模块数据记录，字段含义和取值约束见参数说明。
     *
     * @param phone 手机号
     * @param password 密码
     * @param mfaCode 管理员二次验证码
     */
    public record Login(
            @NotNull @Pattern(regexp = "^1\\d{10}$") String phone,
            @NotNull @Size(min = 10, max = 128) String password,
            @Pattern(regexp = "^\\d{6}$") String mfaCode) {}

    /**
     * 模块数据记录，字段含义和取值约束见参数说明。
     *
     * @param id 用户标识
     * @param version 版本
     * @param createdAt 创建时间
     * @param updatedAt 更新时间
     * @param displayName 昵称
     * @param phoneMasked 脱敏手机号
     * @param platform 所属平台
     * @param status 启停
     * @param schoolId 院校
     * @param majorId 专业
     * @param avatarIndex 默认头像序号
     * @param avatarAssetId 本人头像
     * @param roleIds 角色标识
     */
    public record User(
            UUID id,
            long version,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            String displayName,
            String phoneMasked,
            String platform,
            String status,
            UUID schoolId,
            UUID majorId,
            List<UUID> roleIds,
            int avatarIndex,
            UUID avatarAssetId) {}

    /**
     * 模块数据记录，字段含义和取值约束见参数说明。
     *
     * @param accessToken 内存使用的访问令牌
     * @param expiresIn 有效秒数
     * @param platform 平台
     * @param user 用户
     * @param csrfToken 双提交值
     */
    public record Session(
            String accessToken, long expiresIn, String platform, User user, String csrfToken) {}

    /**
     * 内部返回值，刷新令牌仅经 HttpOnly Cookie 发送。
     *
     * @param session 公开会话
     * @param refreshToken 原始刷新值
     */
    public record Issued(Session session, String refreshToken) {}
}
