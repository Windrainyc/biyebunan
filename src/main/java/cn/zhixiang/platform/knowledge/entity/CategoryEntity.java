package cn.zhixiang.platform.knowledge.entity;

import java.time.OffsetDateTime;
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
 * @param parentId 父级
 * @param sortOrder 顺序
 * @param status 启停
 */
public record CategoryEntity(
        UUID id,
        long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        String name,
        String code,
        UUID parentId,
        long sortOrder,
        String status) {}
