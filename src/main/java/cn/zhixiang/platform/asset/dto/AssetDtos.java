package cn.zhixiang.platform.asset.dto;

import jakarta.validation.constraints.*;

import java.time.OffsetDateTime;
import java.util.*;

/** 上传和资产响应；对象键和签名URL不进入普通资产响应。 */
public final class AssetDtos {
    private AssetDtos() {}

    /**
     * 上传申请。
     *
     * @param ownerType PROJECT/RECEIPT/KNOWLEDGE/FORUM；学生仅允许FORUM
     * @param ownerId 所属项目、订单、知识内容或已存在帖子
     * @param filename 原文件名，不作为对象键
     * @param size 字节大小
     * @param sha256 内容摘要
     * @param mimeType 媒体类型
     */
    public record Upload(
            @NotNull @Pattern(regexp = "PROJECT|RECEIPT|KNOWLEDGE|FORUM") String ownerType,
            @NotNull UUID ownerId,
            @NotBlank @Size(max = 200) String filename,
            @NotNull @Min(1) @Max(2147483648L) Long size,
            @NotNull @Pattern(regexp = "[a-f0-9]{64}") String sha256,
            @NotBlank @Size(max = 200) String mimeType) {}

    /**
     * 短时上传授权。
     *
     * @param uploadId 上传会话
     * @param assetId 资产ID
     * @param url 短时签名地址
     * @param method HTTP方法
     * @param headers 必须使用的请求头
     * @param objectKey 服务端生成键
     * @param expiresAt 到期时间
     * @param maxSize 授权字节大小
     */
    public record Grant(
            UUID uploadId,
            UUID assetId,
            String url,
            String method,
            Map<String, String> headers,
            String objectKey,
            OffsetDateTime expiresAt,
            long maxSize) {
        /**
         * 脱敏日志。
         *
         * @return 安全说明
         */
        @Override
        public String toString() {
            return "UploadGrant[redacted]";
        }
    }

    /**
     * 资产处理状态。
     *
     * @param id ID
     * @param version 版本
     * @param createdAt 创建时间
     * @param updatedAt 更新时间
     * @param filename 文件名
     * @param size 字节数
     * @param sha256 摘要
     * @param mimeType 媒体类型
     * @param state 处理状态
     * @param storageId 存储配置
     */
    public record View(
            UUID id,
            long version,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            String filename,
            long size,
            String sha256,
            String mimeType,
            String state,
            UUID storageId) {}

    /**
     * 私有下载授权。
     *
     * @param url 一分钟有效地址
     * @param expiresAt 到期时间
     */
    public record Download(String url, OffsetDateTime expiresAt) {
        /**
         * 脱敏日志。
         *
         * @return 安全说明
         */
        @Override
        public String toString() {
            return "DownloadGrant[redacted]";
        }
    }
}
