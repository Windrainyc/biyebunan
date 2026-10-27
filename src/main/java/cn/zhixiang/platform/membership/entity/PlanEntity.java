package cn.zhixiang.platform.membership.entity;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * 持久化查询记录，仅供服务层映射和业务判断。
 *
 * @param id 标识
 * @param name 名称
 * @param code 编码
 * @param status 状态
 * @param version CAS
 * @param createdAt 创建
 * @param updatedAt 更新
 */
public record PlanEntity(
        UUID id,
        String name,
        String code,
        String status,
        long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {}
