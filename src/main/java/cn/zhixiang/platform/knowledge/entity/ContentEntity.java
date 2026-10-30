package cn.zhixiang.platform.knowledge.entity;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * 内部内容行。
 *
 * @param id ID
 * @param version 并发版本
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 * @param title 标题
 * @param categoryId 分类
 * @param type 类型
 * @param assetId 资产
 * @param markdown 正文
 * @param audience 范围
 * @param projectId 项目
 * @param state 状态
 * @param publishedVersionId 快照
 * @param indexState 索引状态
 * @param coverAssetId 扫描通过的图片封面，可空
 * @param indexGeneration 期望索引代次
 */
public record ContentEntity(
        UUID id,
        long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        String title,
        UUID categoryId,
        String type,
        UUID assetId,
        String markdown,
        String audience,
        UUID projectId,
        String state,
        UUID publishedVersionId,
        String indexState,
        long indexGeneration,
        UUID coverAssetId) {}
