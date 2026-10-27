package cn.zhixiang.platform.asset.entity;

import java.util.UUID;

/**
 * 本次领取的租约。
 *
 * @param assetId 资产
 * @param generation 代次
 * @param attempts 已执行次数
 */
public record AssetTaskJobEntity(UUID assetId, long generation, int attempts) {}
