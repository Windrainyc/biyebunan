package cn.zhixiang.platform.model.entity;

import cn.zhixiang.platform.model.dto.ModelDtos;

import java.util.*;

/**
 * 持久化查询记录，仅供服务层映射和业务判断。
 *
 * @param id 标识
 * @param version CAS
 * @param createdAt 创建
 * @param updatedAt 更新
 * @param name 名称
 * @param modelId 所属模型
 * @param maskedSuffix 脱敏尾部
 * @param rotatedAt 轮换时间
 * @param secretRef 加密引用，仅内部使用
 * @param status 状态
 */
public record KeyEntity(
        UUID id,
        long version,
        java.time.OffsetDateTime createdAt,
        java.time.OffsetDateTime updatedAt,
        String name,
        UUID modelId,
        String maskedSuffix,
        ModelDtos.Status status,
        java.time.OffsetDateTime rotatedAt,
        UUID secretRef) {}
