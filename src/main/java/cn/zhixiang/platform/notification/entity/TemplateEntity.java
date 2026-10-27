package cn.zhixiang.platform.notification.entity;

import cn.zhixiang.platform.notification.dto.TemplateDtos;

import org.apache.ibatis.annotations.*;

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
 * @param channel 发送渠道
 * @param providerTemplateId 供应商模板编号或站内渲染器模板代码
 * @param status 状态
 */
public record TemplateEntity(
        UUID id,
        long version,
        java.time.OffsetDateTime createdAt,
        java.time.OffsetDateTime updatedAt,
        String name,
        String code,
        TemplateDtos.Channel channel,
        String providerTemplateId,
        TemplateDtos.Status status) {}
