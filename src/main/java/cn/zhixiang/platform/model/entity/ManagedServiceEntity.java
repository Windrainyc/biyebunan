package cn.zhixiang.platform.model.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 官方服务持久状态。
 *
 * @param provider 供应商
 * @param version 版本
 * @param enabled 是否允许新调用
 * @param secretRef 密文引用
 * @param checkedAt 最近检查时间
 * @param checkError 安全错误码
 * @param availableCodes 已核对模型代码
 * @param updatedAt 更新时间
 */
public record ManagedServiceEntity(
        String provider,
        long version,
        boolean enabled,
        UUID secretRef,
        OffsetDateTime checkedAt,
        String checkError,
        String availableCodes,
        OffsetDateTime updatedAt) {}
