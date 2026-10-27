package cn.zhixiang.platform.configuration.entity;

import cn.zhixiang.platform.configuration.dto.DomainDtos;

import java.util.*;

/**
 * 持久化查询记录，仅供服务层映射和业务判断。
 *
 * @param id 标识
 * @param version CAS
 * @param createdAt 创建
 * @param updatedAt 更新
 * @param name 配置展示名称
 * @param hostname 域名，按小写保存，不含协议端口或路径
 * @param platform 服务平台
 * @param certificateRef 部署层证书别名，不接收私钥或任意路径
 * @param status 是否启用配置；不代表 DNS 和 TLS 已部署
 */
public record DomainEntity(
        UUID id,
        long version,
        java.time.OffsetDateTime createdAt,
        java.time.OffsetDateTime updatedAt,
        String name,
        String hostname,
        DomainDtos.Platform platform,
        String certificateRef,
        DomainDtos.Status status) {}
