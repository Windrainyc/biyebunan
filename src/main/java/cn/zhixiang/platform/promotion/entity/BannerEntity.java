package cn.zhixiang.platform.promotion.entity;

import cn.zhixiang.platform.promotion.dto.BannerDtos;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 横幅持久化投影。
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
public record BannerEntity(
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
