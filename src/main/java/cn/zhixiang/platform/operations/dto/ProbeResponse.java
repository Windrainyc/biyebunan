package cn.zhixiang.platform.operations.dto;

/**
 * 对外仅包含健康状态。
 *
 * @param status UP或DOWN
 */
public record ProbeResponse(String status) {}
