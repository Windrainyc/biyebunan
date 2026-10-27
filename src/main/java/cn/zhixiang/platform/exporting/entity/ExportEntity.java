package cn.zhixiang.platform.exporting.entity;

import java.time.*;
import java.util.*;

/**
 * 持久任务记录。
 *
 * @param id 任务ID
 * @param createdBy 申请者
 * @param platform 申请平台
 * @param kind 导出类型
 * @param fromDate 首日
 * @param toDate 末日
 * @param storageId 私有存储配置
 * @param state 任务状态
 * @param attempts 已尝试次数
 * @param generation 租约代次
 * @param leaseUntil 租约截止
 * @param objectKey 已完成对象键
 * @param objectVersion 对象版本
 * @param resultExpiresAt 下载截止
 * @param errorCode 稳定错误码
 * @param version CAS版本
 * @param createdAt 创建时间
 * @param updatedAt 修改时间
 * @param includesChat 是否包含需额外权限的审计元数据
 */
public record ExportEntity(
        UUID id,
        UUID createdBy,
        String platform,
        String kind,
        LocalDate fromDate,
        LocalDate toDate,
        UUID storageId,
        String state,
        int attempts,
        long generation,
        OffsetDateTime leaseUntil,
        String objectKey,
        String objectVersion,
        OffsetDateTime resultExpiresAt,
        String errorCode,
        long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        boolean includesChat) {}
