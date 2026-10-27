package cn.zhixiang.platform.knowledge.service;

import cn.zhixiang.platform.knowledge.mapper.EmbeddingMapper;
import cn.zhixiang.platform.knowledge.port.EmbeddingGateway;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** 嵌入调用在数据库事务外执行，固定向量空间与租约代次防止旧结果覆盖。 */
@Service
public class EmbeddingService {
    private final EmbeddingMapper mapper;
    private final EmbeddingGateway gateway;
    private final TransactionTemplate tx;

    /**
     * 创建嵌入执行器。
     *
     * @param mapper 持久任务
     * @param gateway 配置化嵌入客户端
     * @param tx 短事务
     */
    public EmbeddingService(
            EmbeddingMapper mapper, EmbeddingGateway gateway, TransactionTemplate tx) {
        this.mapper = mapper;
        this.gateway = gateway;
        this.tx = tx;
    }

    /**
     * 执行一项；默认关闭时不访问供应商。协议失败明确标记FAILED。
     *
     * @return 是否领取了任务
     */
    public boolean runOne() {
        if (!gateway.enabled()) return false;
        var task =
                tx.execute(
                        s -> {
                            mapper.expire(gateway.profile());
                            mapper.seed(gateway.profile());
                            return mapper.claim(gateway.profile());
                        });
        if (task == null) return false;
        cn.zhixiang.platform.knowledge.dto.EmbeddingResult result;
        try {
            result = gateway.embed(task.body());
        } catch (RuntimeException e) {
            tx.executeWithoutResult(
                    s -> {
                        mapper.audit(task.profile(), "INDEX", null, null, "EMBEDDING_FAILED");
                        mapper.finish(task, "EMBEDDING_FAILED", null);
                    });
            return true;
        }
        tx.executeWithoutResult(
                s -> {
                    mapper.audit(task.profile(), "INDEX", null, result.inputTokens(), null);
                    if (mapper.finish(task, null, result.inputTokens()) == 1)
                        mapper.insert(task, result.vector(), gateway.dimensions());
                });
        return true;
    }
}
