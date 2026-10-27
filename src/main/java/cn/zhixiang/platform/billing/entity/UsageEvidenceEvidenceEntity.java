package cn.zhixiang.platform.billing.entity;

import org.apache.ibatis.annotations.*;

import java.util.UUID;

/**
 * 可信调用证据，不包含提示或回答正文。
 *
 * @param runId 运行
 * @param kind 证据类型
 * @param inputTokens 输入词元
 * @param outputTokens 输出词元
 * @param cachedTokens 缓存词元
 * @param providerRequestId 供应商请求标识
 * @param leaseOwner 执行器
 * @param generation 代次
 */
public record UsageEvidenceEvidenceEntity(
        UUID runId,
        String kind,
        Long inputTokens,
        Long outputTokens,
        Long cachedTokens,
        String providerRequestId,
        UUID leaseOwner,
        long generation) {}
