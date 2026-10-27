package cn.zhixiang.platform.membership.entity;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * 持久化查询记录，仅供服务层映射和业务判断。
 *
 * @param id 标识
 * @param version CAS
 * @param createdAt 创建
 * @param updatedAt 更新
 * @param userId 用户
 * @param planId 套餐
 * @param planVersionId 固定权益版本，仅内部使用
 * @param startsAt 起点
 * @param expiresAt 终点
 * @param state 计算状态
 */
public record MemberEntity(
        UUID id,
        long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        UUID userId,
        UUID planId,
        UUID planVersionId,
        OffsetDateTime startsAt,
        OffsetDateTime expiresAt,
        String state) {}
