package cn.zhixiang.platform.configuration.entity;

import java.util.UUID;

/**
 * 固定版本迁移任务。
 *
 * @param assetId 资产
 * @param sourceId 源配置
 * @param targetId 目标配置
 * @param sourceKey 源私有键
 * @param sourceVersion 源对象版本
 * @param sourceAssetVersion 源数据库版本
 * @param generation 租约代次
 * @param size 字节数
 * @param sha256 原摘要
 * @param mimeType 类型
 */
public record StorageMigrationEntity(
        UUID assetId,
        UUID sourceId,
        UUID targetId,
        String sourceKey,
        String sourceVersion,
        long sourceAssetVersion,
        long generation,
        long size,
        String sha256,
        String mimeType) {}
