package cn.zhixiang.platform.callback.dto;

import java.util.UUID;

/**
 * 已验签并严格解析的上传通知。
 *
 * @param uploadId 服务端上传会话
 * @param bucket 配置桶
 * @param object 隔离对象键
 * @param size 字节数
 * @param etag 对象ETag
 */
public record OssCallbackRequest(
        UUID uploadId, String bucket, String object, long size, String etag) {}
