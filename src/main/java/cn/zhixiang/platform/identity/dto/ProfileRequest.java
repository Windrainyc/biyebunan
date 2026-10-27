package cn.zhixiang.platform.identity.dto;

import jakarta.validation.constraints.*;

import java.util.UUID;

/**
 * 本人资料输入，没有角色、状态、密码或中介归属字段。
 *
 * @param displayName 昵称1至200字符
 * @param schoolId 启用院校
 * @param majorId 该院校下启用专业
 */
public record ProfileRequest(
        @NotBlank @Size(max = 200) String displayName,
        @NotNull UUID schoolId,
        @NotNull UUID majorId) {}
