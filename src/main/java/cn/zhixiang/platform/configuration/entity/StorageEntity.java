package cn.zhixiang.platform.configuration.entity;

import cn.zhixiang.platform.configuration.dto.StorageDtos;

import org.apache.ibatis.annotations.*;

import java.util.*;

/**
 * 持久化查询记录，仅供服务层映射和业务判断。
 *
 * @param id 标识
 * @param version CAS
 * @param createdAt 创建
 * @param updatedAt 更新
 * @param name 配置展示名称
 * @param provider 对象存储供应商
 * @param bucket 私有桶名称
 * @param region 区域代码
 * @param endpoint 对象存储服务根地址
 * @param credentialRef 受保护运行配置中的凭证别名，不接收明文密钥或任意文件路径
 * @param access 访问级别，仅私有
 * @param status 是否允许业务选择此配置
 */
public record StorageEntity(
        UUID id,
        long version,
        java.time.OffsetDateTime createdAt,
        java.time.OffsetDateTime updatedAt,
        String name,
        StorageDtos.Provider provider,
        String bucket,
        String region,
        String endpoint,
        String credentialRef,
        StorageDtos.Access access,
        StorageDtos.Status status) {}
