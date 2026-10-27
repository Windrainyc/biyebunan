package cn.zhixiang.platform.knowledge.service;

import cn.zhixiang.platform.identity.model.Actor;
import cn.zhixiang.platform.knowledge.dto.ContentDtos;
import cn.zhixiang.platform.knowledge.dto.KnowledgeQueryDtos;
import cn.zhixiang.platform.knowledge.mapper.SemanticSearchMapper;
import cn.zhixiang.platform.knowledge.port.EmbeddingGateway;
import cn.zhixiang.platform.shared.dto.PageResult;
import cn.zhixiang.platform.shared.exception.BusinessException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/** 查询向量在事务外获取，随后在同一只读快照中执行权限过滤和分页计数。 */
@Service
public class SemanticSearchService {
    private final EmbeddingGateway gateway;
    private final SemanticSearchMapper mapper;
    private final TransactionTemplate tx;
    private final TransactionTemplate writeTx;
    private final cn.zhixiang.platform.knowledge.mapper.EmbeddingMapper audit;

    /**
     * 创建检索用例。
     *
     * @param audit 嵌入用量审计
     * @param gateway 固定嵌入客户端
     * @param mapper 带权限的向量查询
     * @param manager 事务管理器
     */
    public SemanticSearchService(
            EmbeddingGateway gateway,
            SemanticSearchMapper mapper,
            PlatformTransactionManager manager,
            cn.zhixiang.platform.knowledge.mapper.EmbeddingMapper audit) {
        this.gateway = gateway;
        this.audit = audit;
        this.writeTx = new TransactionTemplate(manager);
        this.mapper = mapper;
        tx = new TransactionTemplate(manager);
        tx.setReadOnly(true);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    /**
     * 显式语义模式；未启用返回503，不静默改成关键词模式。
     *
     * @param q 请求过滤与分页
     * @param actor 服务端学生身份
     * @return 带引用的真实相似度结果
     * @throws BusinessException 非学生、非法筛选或嵌入不可用
     */
    public PageResult<KnowledgeQueryDtos.Hit> search(ContentDtos.Query q, Actor actor) {
        if (!actor.platform().equals("student"))
            throw new BusinessException(403, "FORBIDDEN", "仅学生检索入口");
        if (q.q().isBlank() || q.status() != null && !q.status().equals("PUBLISHED"))
            throw new BusinessException(400, "INVALID_FILTER", "检索词必填且只能检索已发布内容");
        if (!gateway.enabled())
            throw new BusinessException(503, "EMBEDDING_UNAVAILABLE", "嵌入服务未启用");
        cn.zhixiang.platform.knowledge.dto.EmbeddingResult result;
        try {
            result = gateway.embed(q.q());
        } catch (RuntimeException e) {
            writeTx.executeWithoutResult(
                    s ->
                            audit.audit(
                                    gateway.profile(),
                                    "SEARCH",
                                    actor.id(),
                                    null,
                                    "EMBEDDING_FAILED"));
            throw e;
        }
        writeTx.executeWithoutResult(
                s ->
                        audit.audit(
                                gateway.profile(),
                                "SEARCH",
                                actor.id(),
                                result.inputTokens(),
                                null));
        return tx.execute(
                s ->
                        new PageResult<>(
                                mapper
                                        .search(q, actor.id(), gateway.profile(), result.vector())
                                        .stream()
                                        .map(
                                                r ->
                                                        new KnowledgeQueryDtos.Hit(
                                                                r.contentId(),
                                                                r.versionId(),
                                                                r.title(),
                                                                r.snippet(),
                                                                r.score(),
                                                                List.of(
                                                                        new KnowledgeQueryDtos
                                                                                .Citation(
                                                                                "KNOWLEDGE",
                                                                                r.versionId(),
                                                                                r.chunkId(),
                                                                                0,
                                                                                r.quote()))))
                                        .toList(),
                                q.page(),
                                q.size(),
                                mapper.count(q, actor.id(), gateway.profile(), result.vector())));
    }
}
