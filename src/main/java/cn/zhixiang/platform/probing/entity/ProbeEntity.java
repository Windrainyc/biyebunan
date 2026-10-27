package cn.zhixiang.platform.probing.entity;

import org.apache.ibatis.annotations.*;

import java.util.UUID;

/**
 * 任务执行快照。
 *
 * @param id 任务
 * @param kind 类型
 * @param targetId 配置
 * @param targetVersion 申请时版本
 * @param createdBy 申请者
 * @param generation 租约代次
 * @param attempts 领取次数
 */
public record ProbeEntity(
        UUID id,
        String kind,
        UUID targetId,
        long targetVersion,
        UUID createdBy,
        long generation,
        int attempts) {}
