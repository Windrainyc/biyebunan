package cn.zhixiang.platform.chat.entity;

import java.util.UUID;

/**
 * 领取结果，不包含正文或供应商密钥。
 *
 * @param id 运行
 * @param traceparent 持久化请求父Span，可空
 * @param generation fencing代次
 */
public record RunLeaseLeaseEntity(UUID id, long generation, String traceparent) {}
