package cn.zhixiang.platform.storage.port;

import java.time.OffsetDateTime;
import java.util.Map;

/** 对象存储统一端口。调用者先完成资源授权；不得在长数据库事务中执行网络读写。 */
public interface ObjectStorage {
    /**
     * 对象位置，不包含凭证值。
     *
     * @param provider 供应商类型
     * @param endpoint 部署层白名单端点
     * @param region 区域
     * @param bucket 私有桶
     * @param credentialRef 凭证别名
     */
    record Target(
            String provider, String endpoint, String region, String bucket, String credentialRef) {}

    /**
     * 短时签名授权，不得写入日志。
     *
     * @param url 签名URL
     * @param headers 必须原样提交的签名请求头
     * @param expiresAt 到期时间
     */
    record Grant(String url, Map<String, String> headers, OffsetDateTime expiresAt) {
        /**
         * 防止日志泄漏授权URL。
         *
         * @return 脱敏文本
         */
        @Override
        public String toString() {
            return "StorageGrant[redacted]";
        }
    }

    /**
     * 对象真实元数据。
     *
     * @param size 字节大小
     * @param etag 服务端ETag，用于条件读写，不等于SHA256
     * @param versionId 对象存储版本，可空
     * @param contentType 实际声明的媒体类型
     */
    record Metadata(long size, String etag, String versionId, String contentType) {}

    /**
     * 生成受大小、内容类型与SHA256约束的短时PUT授权。
     *
     * @param target 配置
     * @param key 服务端生成对象键
     * @param size 字节大小
     * @param mime 媒体类型
     * @param sha256 小写十六进制SHA256
     * @return 五分钟内有效的授权
     */
    Grant upload(Target target, String key, long size, String mime, String sha256);

    /**
     * 服务端生成的小型报告写入私有对象；调用方必须分配全新键。
     *
     * @param target 私有配置
     * @param key 唯一服务端键
     * @param bytes 内容，最多20MiB
     * @param mime 媒体类型
     * @return 已写入版本元数据
     */
    Metadata write(Target target, String key, byte[] bytes, String mime);

    /**
     * 只读探测目标桶存在且当前凭证能访问，不代表写入权限已验证。
     *
     * @param target 固定部署白名单配置
     */
    void probe(Target target);

    /**
     * HEAD读取对象，缺失返回404业务错误。
     *
     * @param target 配置
     * @param key 对象键
     * @return 元数据
     */
    Metadata stat(Target target, String key);

    /**
     * 以ETag/版本固定对象后流式计算真实摘要，不相信客户端自报元数据。
     *
     * @param target 配置
     * @param key 对象键
     * @param expected 先前HEAD元数据
     * @return 真实SHA256；大小变化或读取失败抛业务错误
     */
    String sha256(Target target, String key, Metadata expected);

    /**
     * 复制已校验对象到服务端私有新键，防止原上传URL重放覆盖交付物。
     *
     * @param target 配置
     * @param sourceKey 上传键
     * @param expected 固定源版本或ETag
     * @param finalKey 新的服务端生成键
     * @return 复制后的元数据
     */
    Metadata promote(Target target, String sourceKey, Metadata expected, String finalKey);

    /**
     * 私有下载授权；业务层必须先确认资源可见、状态和发布版本。
     *
     * @param target 配置
     * @param key 对象键
     * @param versionId 固定版本，可空
     * @return 一分钟有效授权
     */
    Grant download(Target target, String key, String versionId);

    /**
     * 删除业务已批准清理的指定对象或版本。
     *
     * @param target 配置
     * @param key 对象键
     * @param versionId 版本，可空
     */
    void remove(Target target, String key, String versionId);

    /**
     * 打开固定版本/ETag的流，供扫描适配器使用，调用者必须关闭。
     *
     * @param target 位置
     * @param key 对象键
     * @param expected 元数据
     * @return 需关闭的输入流
     */
    java.io.InputStream open(Target target, String key, Metadata expected);
}
