package cn.zhixiang.platform.billing.dto;

import jakarta.validation.constraints.*;

import org.springframework.web.bind.annotation.*;

/**
 * 核对理由。
 *
 * @param reason 不含密钥的业务原因
 */
public record ReconciliationReasonRequest(@NotBlank @Size(max = 1000) String reason) {}
