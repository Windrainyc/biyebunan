package cn.zhixiang.platform.chat.dto;

import jakarta.validation.constraints.*;

import java.time.OffsetDateTime;
import java.util.*;

/** 生成任务输入与只读状态，不暴露模型凭证或内部上下文。 */
public final class RunDtos {
    private RunDtos() {}

    /**
     * 创建运行。
     *
     * @param conversationId 本人会话
     * @param clientMessageId 学生范围永久幂等ID
     * @param text 问题
     * @param modelId 模型
     * @param sourceVersionIds 显式引用知识快照，最多8个
     * @param maxOutputTokens 输出词元上限
     */
    public record Create(
            @NotNull UUID conversationId,
            @NotNull UUID clientMessageId,
            @NotBlank @Size(max = 16000) String text,
            UUID modelId,
            @NotNull @Size(max = 8) List<@NotNull UUID> sourceVersionIds,
            @Min(1) @Max(8192) int maxOutputTokens) {
        /**
         * 不把私人提问写入对象日志。
         *
         * @return 脱敏描述
         */
        @Override
        public String toString() {
            return "RunCreate[redacted]";
        }
    }

    /**
     * 运行状态。
     *
     * @param id ID
     * @param version CAS版本
     * @param createdAt 创建
     * @param updatedAt 更新
     * @param conversationId 会话
     * @param state 业务状态
     * @param billingState 账务状态
     * @param modelId 模型
     * @param lastSequence 最高流序号
     * @param errorCode 稳定错误码或null
     */
    public record View(
            UUID id,
            long version,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            UUID conversationId,
            String state,
            String billingState,
            UUID modelId,
            long lastSequence,
            String errorCode) {}
}
