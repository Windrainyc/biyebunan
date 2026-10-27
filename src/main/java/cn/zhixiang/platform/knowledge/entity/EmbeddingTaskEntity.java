package cn.zhixiang.platform.knowledge.entity;

import java.util.UUID;

/**
 * 单分片嵌入租约。
 *
 * @param chunkId 当前分片标识
 * @param profile 向量空间指纹
 * @param generation 租约代次
 * @param body 当前分片正文
 */
public record EmbeddingTaskEntity(UUID chunkId, String profile, long generation, String body) {}
