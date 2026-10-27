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
 * @param code 代码
 * @param provider 供应商
 * @param endpoint 端点
 * @param contextLimit 上下文词元数
 * @param maxOutputTokens 最大输出词元数
 * @param status 状态
 */
public record ModelEntity(
        UUID id,
        long version,
        java.time.OffsetDateTime createdAt,
        java.time.OffsetDateTime updatedAt,
        String name,
        String code,
        String provider,
        String endpoint,
        int contextLimit,
        int maxOutputTokens,
        ModelDtos.Status status) {}
