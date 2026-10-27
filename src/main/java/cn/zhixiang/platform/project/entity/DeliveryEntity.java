package cn.zhixiang.platform.project.entity;

import org.apache.ibatis.annotations.*;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * 内部记录，不返回数据库实体。
 *
 * @param id ID
 * @param version 并发版本
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 * @param kind 类别
 * @param assetId 资产
 * @param note 说明
 * @param projectId 项目
 * @param versionNumber 业务序号
 * @param state 状态
 */
public record DeliveryEntity(
        UUID id,
        long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        String kind,
        UUID assetId,
        String note,
        UUID projectId,
        int versionNumber,
        String state) {}
