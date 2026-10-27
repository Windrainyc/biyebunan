package cn.zhixiang.platform.knowledge.entity;

import java.util.*;

/**
 * 领取任务。
 *
 * @param id 任务
 * @param contentId 内容
 * @param snapshotId 快照
 * @param contentGeneration 内容代次
 * @param generation 租约代次
 * @param attempts 次数
 */
public record IndexTaskEntity(
        UUID id,
        UUID contentId,
        UUID snapshotId,
        long contentGeneration,
        long generation,
        int attempts) {}
