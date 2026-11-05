package cn.zhixiang.platform.model.service;

import cn.zhixiang.platform.model.dto.ManagedModelDtos.Model;

import java.util.List;

/** 经过核对的官方模板；不接受客户端端点，价格保留版本。 */
public final class OfficialModelCatalog {
    private OfficialModelCatalog() {}

    /** 官方完整请求地址。 */
    public static final String ENDPOINT = "https://api.deepseek.com/chat/completions";

    /** 官方价格来源。 */
    public static final String SOURCE = "https://api-docs.deepseek.com/zh-cn/quick_start/pricing/";

    /** 官方价格核对版本。 */
    public static final String VERSION = "deepseek-cny-20261105";

    /** 官方核对日期。 */
    public static final String CHECKED = "2026-11-05";

    /**
     * 官方服务配置操作，遵循版本与权限约束。
     *
     * @return 固定官方模型与高峰成本上界，空闲时段为一半
     */
    public static List<Model> models() {
        return List.of(
                new Model("deepseek-flash", "DeepSeek Flash", false, "2", "8", "0.04"),
                new Model("deepseek-v4-pro", "DeepSeek V4 Pro", false, "9", "27", "0.30"));
    }
}
