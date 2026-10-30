package cn.zhixiang.platform.asset.entity;

import cn.zhixiang.platform.asset.dto.MaterialCategoryDtos;

import java.util.*;

/**
 * 持久化查询记录，仅供服务层映射和业务判断。
 *
 * @param id 标识
 * @param version CAS
 * @param createdAt 创建
 * @param updatedAt 更新
 * @param name 名称
 * @param code 代码
 * @param status 状态
 */
public record MaterialCategoryEntity(
        UUID id,
        long version,
        java.time.OffsetDateTime createdAt,
        java.time.OffsetDateTime updatedAt,
        String name,
        String code,
        MaterialCategoryDtos.Status status) {}
