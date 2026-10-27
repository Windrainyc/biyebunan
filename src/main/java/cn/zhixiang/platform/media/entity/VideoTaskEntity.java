package cn.zhixiang.platform.media.entity;

import java.util.UUID;

/**
 * 视频转码租约与结果。
 *
 * @param assetId 源资产
 * @param state 状态
 * @param generation 租约代次
 * @param attempts 执行次数
 * @param outputKey 成功播放副本键，可空
 * @param objectVersion 播放副本版本，可空
 */
public record VideoTaskEntity(
        UUID assetId,
        String state,
        long generation,
        int attempts,
        String outputKey,
        String objectVersion) {}
