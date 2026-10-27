package cn.zhixiang.platform.billing.dto;

import jakarta.validation.constraints.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 人工证据裁决契约；引用是线下核验凭据编号，不会由服务器自动访问URL。 */
public final class AdjudicationDtos {
    private AdjudicationDtos() {}

    /**
     * 待第二人复核的声明，不能直接触发结算。
     *
     * @param kind USAGE或NO_CHARGE
     * @param inputTokens 输入词元；NO_CHARGE必须null
     * @param outputTokens 输出词元；NO_CHARGE必须null
     * @param cachedTokens 缓存输入词元；NO_CHARGE必须null
     * @param evidenceRef 供应商账单/工单引用，禁止放密钥
     * @param evidenceSha256 原始凭据文件SHA-256，复核者应独立验证
     * @param reason 提交原因
     */
    public record Create(
            @NotNull @Pattern(regexp = "USAGE|NO_CHARGE") String kind,
            @Min(0) @Max(1000000000) Long inputTokens,
            @Min(0) @Max(1000000000) Long outputTokens,
            @Min(0) @Max(1000000000) Long cachedTokens,
            @NotBlank @Size(max = 1000) String evidenceRef,
            @NotNull @Pattern(regexp = "[0-9a-f]{64}") String evidenceSha256,
            @NotBlank @Size(max = 1000) String reason) {}

    /**
     * 独立管理员的审核决定。
     *
     * @param decision APPROVE或REJECT
     * @param reason 独立核查过程及结论，不包含凭证秘密
     */
    public record Decision(
            @NotNull @Pattern(regexp = "APPROVE|REJECT") String decision,
            @NotBlank @Size(max = 1000) String reason) {}

    /**
     * 可审计的人工声明，来源固定MANUAL_ADJUDICATION而非供应商返回。
     *
     * @param id 裁决单
     * @param runId 对应运行
     * @param version CAS版本
     * @param state PENDING/APPROVED/REJECTED
     * @param kind USAGE/NO_CHARGE
     * @param inputTokens 输入词元，可空
     * @param outputTokens 输出词元，可空
     * @param cachedTokens 缓存词元，可空
     * @param evidenceRef 凭据引用
     * @param evidenceSha256 凭据摘要
     * @param reason 提交理由
     * @param createdBy 提交人
     * @param createdAt 提交时间
     * @param reviewedBy 复核人，可空
     * @param reviewReason 复核理由，可空
     * @param reviewedAt 复核时间，可空
     * @param disputed 后到的原始调用证据是否与已批准声明冲突，必须人工复查
     * @param source 固定人工来源
     */
    public record View(
            UUID id,
            UUID runId,
            long version,
            String state,
            String kind,
            Long inputTokens,
            Long outputTokens,
            Long cachedTokens,
            String evidenceRef,
            String evidenceSha256,
            String reason,
            UUID createdBy,
            OffsetDateTime createdAt,
            UUID reviewedBy,
            String reviewReason,
            OffsetDateTime reviewedAt,
            String source,
            boolean disputed) {}
}
