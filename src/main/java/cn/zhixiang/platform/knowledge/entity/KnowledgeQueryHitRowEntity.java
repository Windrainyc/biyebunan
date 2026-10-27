package cn.zhixiang.platform.knowledge.entity;

import java.util.*;

/**
 * 内部命中。
 *
 * @param contentId 内容
 * @param versionId 快照
 * @param title 标题
 * @param snippet 摘要
 * @param score 词法权重
 * @param chunkId 分片
 * @param quote 引用
 */
public record KnowledgeQueryHitRowEntity(
        UUID contentId,
        UUID versionId,
        String title,
        String snippet,
        double score,
        UUID chunkId,
        String quote) {}
