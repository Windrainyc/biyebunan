package cn.zhixiang.platform.knowledge.dto;

import java.time.OffsetDateTime;
import java.util.*;

/** 知识历史及可引用检索结果。 */
public final class KnowledgeQueryDtos {
    private KnowledgeQueryDtos() {}

    /**
     * 不可变发布快照；version固定1，正文不可修改。
     *
     * @param id 快照ID
     * @param version 快照并发版本，固定1
     * @param createdAt 创建时间
     * @param updatedAt 与创建时间一致
     * @param contentId 内容
     * @param versionNumber 发布序号
     * @param assetId 附件
     * @param markdown 正文
     * @param state 当前发布版为PUBLISHED，历史为WITHDRAWN
     */
    public record Revision(
            UUID id,
            long version,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            UUID contentId,
            long versionNumber,
            UUID assetId,
            String markdown,
            String state) {}

    /**
     * 引用原文。
     *
     * @param sourceType KNOWLEDGE
     * @param versionId 快照
     * @param chunkId 索引分片
     * @param page Markdown无页码时为0
     * @param quote 引用原文，最多2000字符
     */
    public record Citation(
            String sourceType, UUID versionId, UUID chunkId, long page, String quote) {}

    /**
     * 单内容关键词命中。
     *
     * @param contentId 内容
     * @param versionId 发布版本
     * @param title 标题
     * @param snippet 最多1000字符摘要
     * @param score 关键词权重，不是向量相似度
     * @param citations 可追溯引用
     */
    public record Hit(
            UUID contentId,
            UUID versionId,
            String title,
            String snippet,
            double score,
            List<Citation> citations) {}
}
