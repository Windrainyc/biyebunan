package cn.zhixiang.platform.identity.entity;

import org.apache.ibatis.annotations.*;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * 内部角色行。
 *
 * @param id 标识
 * @param version 版本
 * @param createdAt 创建时间
 * @param updatedAt 修改时间
 * @param name 名称
 * @param code 代码
 * @param platform 平台
 * @param dataScope 数据范围
 * @param status 状态
 * @param builtIn 是否内置
 */
public record RoleEntity(
        UUID id,
        long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        String name,
        String code,
        String platform,
        String dataScope,
        String status,
        boolean builtIn) {}
