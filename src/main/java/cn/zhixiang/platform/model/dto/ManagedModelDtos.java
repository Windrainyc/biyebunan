package cn.zhixiang.platform.model.dto;

import jakarta.validation.constraints.*;

import java.time.OffsetDateTime;
import java.util.List;

/** 官方服务的请求与安全响应。 */
public final class ManagedModelDtos {
    private ManagedModelDtos() {}

    /**
     * 保存配置，保存后停止新调用，需重新检查并显式启用。
     *
     * @param secretValue 新密钥，空值保留已保存密钥
     * @param codes 选择的官方模型代码
     */
    public record Save(
            @Size(max = 4096) String secretValue,
            @NotNull @Size(max = 2) List<@NotBlank String> codes) {
        /**
         * @return 不含密钥的日志说明
         */
        @Override
        public String toString() {
            return "ManagedModelSave[redacted]";
        }
    }

    /**
     * 服务开关。
     *
     * @param enabled 是否启用
     */
    public record Switch(@NotNull Boolean enabled) {}

    /**
     * 官方模型模板；价格为人民币每百万词元。
     *
     * @param code 官方代码
     * @param name 展示名
     * @param selected 是否选用
     * @param input 输入高峰价格
     * @param output 输出高峰价格
     * @param cached 缓存命中高峰价格
     */
    public record Model(
            String code,
            String name,
            boolean selected,
            String input,
            String output,
            String cached) {}

    /**
     * 安全状态。
     *
     * @param version 乐观锁
     * @param enabled 已启用
     * @param keyConfigured 已存密钥
     * @param checkedAt 最近只读检查
     * @param checkError 安全中文原因
     * @param availableCodes 官方返回的受支持模型
     * @param models 平台维护模型
     * @param endpoint 自动配置端点
     * @param priceSource 官方来源
     * @param priceVersion 价格版本
     * @param priceCheckedAt 官方价格核对日期
     */
    public record View(
            long version,
            boolean enabled,
            boolean keyConfigured,
            OffsetDateTime checkedAt,
            String checkError,
            List<String> availableCodes,
            List<Model> models,
            String endpoint,
            String priceSource,
            String priceVersion,
            String priceCheckedAt) {}
}
