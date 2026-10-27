package cn.zhixiang.platform.asset.entity;

import java.util.UUID;

/**
 * 已登记对象回收租约。
 *
 * @param id 回收任务
 * @param assetId 资产
 * @param storageId 固定存储配置
 * @param objectKey 完整对象键
 * @param kind UPLOAD或PRIVATE
 * @param attemptGeneration 原验证任务代次，可空
 * @param generation 执行代次
 */
public record AssetGcEntity(
        UUID id,
        UUID assetId,
        UUID storageId,
        String objectKey,
        String kind,
        Long attemptGeneration,
        long generation) {}
