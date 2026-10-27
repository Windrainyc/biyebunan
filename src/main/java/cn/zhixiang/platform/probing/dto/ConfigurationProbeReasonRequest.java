package cn.zhixiang.platform.probing.dto;

import jakarta.validation.constraints.*;

import org.springframework.web.bind.annotation.*;

/**
 * 探测申请。
 *
 * @param reason 必填原因，最多1000字
 */
public record ConfigurationProbeReasonRequest(@NotBlank @Size(max = 1000) String reason) {}
