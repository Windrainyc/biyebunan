package cn.zhixiang.platform.forum.dto;

import jakarta.validation.constraints.*;

import java.time.OffsetDateTime;
import java.util.*;

/** 举报协议，举报原因不可被处理结果覆盖。 */
public final class ReportDtos {
    private ReportDtos() {}

    /**
     * 举报理由。
     *
     * @param reason 非空理由
     */
    public record Reason(@NotBlank @Size(max = 200) String reason) {}

    /**
     * 管理员结论。
     *
     * @param decision RESOLVED或DISMISSED
     * @param reason 处理依据
     */
    public record Decision(
            @NotNull @Pattern(regexp = "RESOLVED|DISMISSED") String decision,
            @NotBlank @Size(max = 200) String reason) {}

    /**
     * 举报响应，仅创建者的创建响应和管理员目录可访问。
     *
     * @param id 标识
     * @param version 版本
     * @param createdAt 创建
     * @param updatedAt 更新
     * @param postId 帖子
     * @param reporterId 举报人
     * @param reason 原始理由
     * @param state 处理状态
     */
    public record View(
            UUID id,
            long version,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            UUID postId,
            UUID reporterId,
            String reason,
            String state) {}
}
