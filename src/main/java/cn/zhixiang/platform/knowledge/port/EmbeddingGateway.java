package cn.zhixiang.platform.knowledge.port;

import cn.zhixiang.platform.knowledge.dto.EmbeddingResult;

/** 独立嵌入端口，未启用时不允许生成占位向量。 */
public interface EmbeddingGateway {
    /**
     * 查询嵌入配置。
     *
     * @return 部署是否显式开启
     */
    boolean enabled();

    /**
     * 查询嵌入配置。
     *
     * @return 端点、模型、维数对应的固定配置指纹
     */
    String profile();

    /**
     * 查询嵌入配置。
     *
     * @return 1至2000维
     */
    int dimensions();

    /**
     * 对单条文本生成真实向量；调用方不得持有数据库长事务。
     *
     * @param text 已授权待处理文本
     * @return 向量及已知输入用量
     * @throws cn.zhixiang.platform.shared.exception.BusinessException 未配置或供应商协议失败
     */
    EmbeddingResult embed(String text);
}
