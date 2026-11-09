package cn.zhixiang.platform.configuration.dto;

import jakarta.validation.constraints.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 配置请求和响应；保存元数据不会自动修改网络、桶权限或部署证书。 */
public final class StorageDtos {
    private StorageDtos() {}

    /** 配置启停状态。 */
    public enum Status {
        /** 可选择。 */
        ENABLED,
        /** 停用保留历史。 */
        DISABLED
    }

    /** 供应商类型。 */
    public enum Provider {
        /** 阿里云 OSS。 */
        ALIYUN_OSS,
        /** 仅用于读取历史记录；已停止支持，禁止新建。 */
        S3_COMPATIBLE
    }

    /** 访问级别。 */
    public enum Access {
        /** 仅鉴权签名访问。 */
        PRIVATE
    }

    /**
     * 配置写入。
     *
     * @param name 配置展示名称
     * @param provider 对象存储供应商
     * @param bucket 私有桶名称
     * @param region 区域代码
     * @param endpoint 对象存储服务根地址
     * @param credentialRef 受保护运行配置中的凭证别名，不接收明文密钥或任意文件路径
     * @param access 访问级别，仅私有
     * @param status 是否允许业务选择此配置
     */
    public record Write(
            @NotBlank @Size(max = 200) String name,
            @NotNull Provider provider,
            @NotBlank @Pattern(regexp = "[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]") String bucket,
            @NotBlank @Size(max = 200) String region,
            @NotBlank @Size(max = 2048) String endpoint,
            @NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]{0,199}") String credentialRef,
            @NotNull Access access,
            @NotNull Status status) {}

    /**
     * 启停请求。
     *
     * @param status 目标状态
     */
    public record StatusWrite(@NotNull Status status) {}

    /**
     * 只读配置响应。
     *
     * @param id 唯一标识
     * @param version CAS版本
     * @param createdAt 创建时间
     * @param updatedAt 更新时间
     * @param name 配置展示名称
     * @param provider 对象存储供应商
     * @param bucket 私有桶名称
     * @param region 区域代码
     * @param endpoint 对象存储服务根地址
     * @param credentialRef 受保护运行配置中的凭证别名，不接收明文密钥或任意文件路径
     * @param access 访问级别，仅私有
     * @param status 是否允许业务选择此配置
     */
    public record View(
            UUID id,
            long version,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            String name,
            Provider provider,
            String bucket,
            String region,
            String endpoint,
            String credentialRef,
            Access access,
            Status status) {}
}
