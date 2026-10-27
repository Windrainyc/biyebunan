package cn.zhixiang.platform.operations.entity;

import org.apache.ibatis.annotations.*;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * 数据库投影。
 *
 * @param id ID
 * @param version 版本
 * @param createdAt 创建时间
 * @param updatedAt 修改时间
 * @param kind 类别
 * @param state 状态
 * @param progress 百分比
 * @param resultAssetId 结果资产
 * @param errorCode 错误码
 */
public record JobEntity(
        UUID id,
        long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        String kind,
        String state,
        long progress,
        UUID resultAssetId,
        String errorCode) {}
