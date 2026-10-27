package cn.zhixiang.platform.chat.dto;

import org.springframework.web.bind.annotation.*;

/**
 * 取消理由，不包含密钥。
 *
 * @param reason 业务理由
 */
public record RunCancelRequest(
        @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 1000)
                String reason) {}
