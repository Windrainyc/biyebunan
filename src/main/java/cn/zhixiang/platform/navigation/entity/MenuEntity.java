package cn.zhixiang.platform.navigation.entity;

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
 * @param platform 平台
 * @param parentId 父项
 * @param path 路由
 * @param permissionCode 权限
 * @param sortOrder 顺序
 * @param status 状态
 */
public record MenuEntity(
        UUID id,
        long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        String name,
        String code,
        String platform,
        UUID parentId,
        String path,
        String permissionCode,
        int sortOrder,
        String status) {}
