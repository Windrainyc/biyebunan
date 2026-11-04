package cn.zhixiang.platform.identity.dto;

import jakarta.validation.constraints.*;

import java.util.UUID;

/**
 * 本人资料输入，没有角色、状态、密码或中介归属字段。
 *
 * @param displayName 昵称1至200字符
 * @param schoolId 启用院校
 * @param avatarIndex 默认头像序号0至9，可空保留旧值
 * @param avatarAssetId 本人头像资产，可空使用默认头像
 * @param majorId 该院校下启用专业
 */
public record ProfileRequest(
        @NotBlank @Size(max = 200) String displayName,
        @NotNull UUID schoolId,
        @NotNull UUID majorId,
        @Min(0) @Max(9) Integer avatarIndex,
        UUID avatarAssetId) {}
