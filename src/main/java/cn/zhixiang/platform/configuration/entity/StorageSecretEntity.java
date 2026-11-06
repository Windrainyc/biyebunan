package cn.zhixiang.platform.configuration.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 加密存储凭证，仅服务内部使用。
 *
 * @param storageId 配置
 * @param version 凭证版本
 * @param accessCipher AK密文
 * @param secretCipher SK密文
 * @param accessTail 脱敏尾号
 * @param checkedAt 最近检查
 * @param checkError 安全错误码
 */
public record StorageSecretEntity(
        UUID storageId,
        long version,
        String accessCipher,
        String secretCipher,
        String accessTail,
        OffsetDateTime checkedAt,
        String checkError) {
    /**
     * 按存储配置的权限、版本与生命周期约束执行操作。
     *
     * @return 防止异常日志泄漏密文
     */
    @Override
    public String toString() {
        return "StorageSecret[redacted]";
    }
}
