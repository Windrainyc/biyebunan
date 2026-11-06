package cn.zhixiang.platform.configuration.dto;

import jakarta.validation.constraints.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/** OSS管理员配置，秘密只写不读。 */
public final class OssDtos {
    private OssDtos() {}

    /**
     * 保存密钥；已有配置填id，目标不可改变，另建配置用于换桶。
     *
     * @param id 已有配置或空
     * @param version 已有密钥版本，新建为0
     * @param bucket 私有桶
     * @param region 区域
     * @param accessKeyId 阿里云RAM子账号AK
     * @param accessKeySecret 阿里云RAM子账号SK
     */
    public record Save(
            UUID id,
            @Min(0) long version,
            @NotBlank @Pattern(regexp = "[a-z0-9][a-z0-9-]{1,61}[a-z0-9]") String bucket,
            @NotBlank @Pattern(regexp = "[a-z]{2}-[a-z0-9]+(?:-[a-z0-9]+)*") String region,
            @NotBlank @Size(max = 128) String accessKeyId,
            @NotBlank @Size(max = 256) String accessKeySecret) {
        /**
         * 按存储配置的权限、版本与生命周期约束执行操作。
         *
         * @return 禁止日志输出密钥
         */
        @Override
        public String toString() {
            return "OssSave[redacted]";
        }
    }

    /**
     * 安全配置状态。
     *
     * @param id 配置标识
     * @param version 密钥CAS版本
     * @param bucket 桶
     * @param region 区域
     * @param endpoint 官方地址
     * @param accessTail AK尾号
     * @param checkedAt 检查时间
     * @param checkError 安全检查错误码
     * @param defaultUpload 是否默认上传
     */
    public record View(
            UUID id,
            long version,
            String bucket,
            String region,
            String endpoint,
            String accessTail,
            OffsetDateTime checkedAt,
            String checkError,
            boolean defaultUpload) {}
}
