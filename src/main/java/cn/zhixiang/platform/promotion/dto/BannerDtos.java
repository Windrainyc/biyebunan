package cn.zhixiang.platform.promotion.dto;

import jakarta.validation.constraints.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 首页横幅接口模型。 */
public final class BannerDtos {
    private BannerDtos() {}

    /** 展示状态。 */
    public enum Status {
        /** 展示。 */
        ENABLED,
        /** 隐藏。 */
        DISABLED
    }

    /**
     * 横幅编辑数据；至少提供海报或详情。
     *
     * @param title 横幅标题
     * @param subtitle 简短说明
     * @param coverAssetId 横幅图片
     * @param posterAssetId 弹窗海报
     * @param body Markdown活动详情
     * @param sortOrder 展示顺序
     * @param status 启停状态
     */
    public record Write(
            @NotBlank @Size(max = 80) String title,
            @Size(max = 160) String subtitle,
            UUID coverAssetId,
            UUID posterAssetId,
            @Size(max = 20000) String body,
            @Min(0) @Max(9999) int sortOrder,
            @NotNull Status status) {}

    /**
     * 状态变更。
     *
     * @param status 目标状态
     */
    public record StatusWrite(@NotNull Status status) {}

    /**
     * 横幅详情。
     *
     * @param id 标识
     * @param version 版本
     * @param createdAt 创建时间
     * @param updatedAt 更新时间
     * @param title 横幅标题
     * @param subtitle 简短说明
     * @param coverAssetId 横幅图片
     * @param posterAssetId 弹窗海报
     * @param body Markdown活动详情
     * @param sortOrder 展示顺序
     * @param status 启停状态
     */
    public record View(
            UUID id,
            long version,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            String title,
            String subtitle,
            UUID coverAssetId,
            UUID posterAssetId,
            String body,
            int sortOrder,
            BannerDtos.Status status) {}
}
