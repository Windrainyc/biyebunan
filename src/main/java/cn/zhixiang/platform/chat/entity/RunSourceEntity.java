package cn.zhixiang.platform.chat.entity;

import java.util.*;

/**
 * 固定引用。
 *
 * @param chunkId 原分片
 * @param versionId 快照
 * @param quote 正文
 * @param page Markdown分片序号，不是PDF页码
 */
public record RunSourceEntity(UUID chunkId, UUID versionId, String quote, int page) {}
