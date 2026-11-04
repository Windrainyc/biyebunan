package cn.zhixiang.platform.project.dto;

import jakarta.validation.constraints.*;

/** 阶段与验收输入，进度百分比不由这些接口直接设置。 */
public final class StageDtos {
    private StageDtos() {}

    /**
     * 管理员阶段变更。
     *
     * @param stage 目标阶段，DONE只能通过学生验收
     * @param reason 变更原因
     */
    public record Change(
            @NotNull
                    @Pattern(
                            regexp =
                                    "PENDING|REQUIREMENTS|PROPOSAL|DEVELOPMENT|DOCUMENTATION|ACCEPTANCE|DONE|PAUSED")
                    String stage,
            @Size(max = 1000) String reason) {}

    /**
     * 学生验收。
     *
     * @param decision ACCEPT或REJECT
     * @param reason 验收意见
     */
    public record Acceptance(
            @NotNull @Pattern(regexp = "ACCEPT|REJECT") String decision,
            @NotBlank @Size(max = 1000) String reason) {}
}
