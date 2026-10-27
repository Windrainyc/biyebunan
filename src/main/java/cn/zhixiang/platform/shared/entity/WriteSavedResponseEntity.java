package cn.zhixiang.platform.shared.entity;

/**
 * 幂等写入的已提交响应记录。
 *
 * @param requestHash 原请求摘要
 * @param responseStatus 状态
 * @param responseBody 原响应
 * @param responseEtag 原版本
 */
public record WriteSavedResponseEntity(
        String requestHash, Integer responseStatus, String responseBody, String responseEtag) {}
