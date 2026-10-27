package cn.zhixiang.platform.identity.entity;

import org.apache.ibatis.annotations.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 持久化查询记录，仅供服务层映射和业务判断。
 *
 * @param id 标识
 * @param phoneDigest 手机摘要
 * @param purpose 用途
 * @param platform 平台
 * @param codeDigest 验证摘要
 * @param state 发送消费状态
 * @param attempts 错误次数
 * @param expiresAt 截止时间
 */
public record VerificationEntity(
        UUID id,
        String phoneDigest,
        String purpose,
        String platform,
        String codeDigest,
        String state,
        int attempts,
        OffsetDateTime expiresAt) {}
