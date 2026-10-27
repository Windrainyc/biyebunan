package cn.zhixiang.platform.knowledge.entity;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * 历史投影。
 *
 * @param id 快照
 * @param version 固定1
 * @param createdAt 创建
 * @param updatedAt 创建
 * @param contentId 内容
 * @param versionNumber 序号
 * @param assetId 附件
 * @param markdown 正文
 * @param state 显示状态
 */
public record KnowledgeQueryRevisionRowEntity(
        UUID id,
        long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        UUID contentId,
        long versionNumber,
        UUID assetId,
        String markdown,
        String state) {}
