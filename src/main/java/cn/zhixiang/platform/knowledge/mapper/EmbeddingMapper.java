package cn.zhixiang.platform.knowledge.mapper;

import cn.zhixiang.platform.knowledge.entity.EmbeddingTaskEntity;

import org.apache.ibatis.annotations.*;

/** 嵌入持久任务与向量写入；新文本索引删除旧分片时自动级联清理。 */
@Mapper
public interface EmbeddingMapper {
    /**
     * 将当前发布代次且未嵌入的分片加入任务，每次最多100条。
     *
     * @param profile 模型配置指纹
     */
    @Insert(
            "INSERT INTO knowledge_embedding_task(chunk_id,profile) SELECT k.id,#{profile} FROM"
                + " knowledge_chunk k JOIN knowledge_content c ON c.id=k.content_id WHERE"
                + " c.deleted_at IS NULL AND c.state='PUBLISHED' AND c.index_state='READY' AND"
                + " c.published_version_id=k.version_id AND c.index_generation=k.generation AND NOT"
                + " EXISTS(SELECT 1 FROM knowledge_embedding_task t WHERE t.chunk_id=k.id AND"
                + " t.profile=#{profile}) ORDER BY k.id LIMIT 100 ON CONFLICT DO NOTHING")
    void seed(String profile);

    /**
     * 原子领取单分片；只领取未开始任务；超时调用不盲目重发。
     *
     * @param profile 模型空间
     * @return 租约或null
     */
    @Select(
            value =
                    """
WITH p AS (
 SELECT t.chunk_id,t.profile FROM knowledge_embedding_task t
 JOIN knowledge_chunk k ON k.id=t.chunk_id JOIN knowledge_content c ON c.id=k.content_id
 WHERE t.profile=#{profile} AND t.state='QUEUED' AND c.deleted_at IS NULL
 AND c.state='PUBLISHED' AND c.index_state='READY'
 AND c.published_version_id=k.version_id AND c.index_generation=k.generation
 ORDER BY t.created_at,t.chunk_id LIMIT 1 FOR UPDATE OF t SKIP LOCKED
), u AS (
 UPDATE knowledge_embedding_task t SET state='RUNNING',generation=generation+1,
 lease_until=now()+interval '2 minutes',updated_at=now() FROM p
 WHERE t.chunk_id=p.chunk_id AND t.profile=p.profile RETURNING t.chunk_id,t.profile,t.generation
)
SELECT u.chunk_id,u.profile,u.generation,k.body FROM u JOIN knowledge_chunk k ON k.id=u.chunk_id
""",
            affectData = true)
    EmbeddingTaskEntity claim(String profile);

    /**
     * 以租约代次完成；网络返回时分片可能已被新索引清除。
     *
     * @param task 当前租约
     * @param error 可空错误码
     * @param tokens 真实可空用量
     * @return 1表示当前代次写入，0表示失效
     */
    @Update(
            "UPDATE knowledge_embedding_task SET state=CASE WHEN CAST(#{error} AS text) IS NULL"
                + " THEN 'SUCCEEDED' ELSE 'FAILED'"
                + " END,error_code=#{error},input_tokens=#{tokens},lease_until=NULL,updated_at=now()"
                + " WHERE chunk_id=#{task.chunkId} AND profile=#{task.profile} AND"
                + " generation=#{task.generation} AND state='RUNNING' AND lease_until>now()")
    int finish(@Param("task") EmbeddingTaskEntity task, String error, Long tokens);

    /**
     * 与任务完成同事务插入；只在当前分片仍存在时成功。
     *
     * @param task 任务
     * @param vector 已验证数值向量
     * @param dimensions 维数
     */
    @Insert(
            "INSERT INTO knowledge_vector(chunk_id,profile,dimensions,embedding)"
                    + " VALUES(#{task.chunkId},#{task.profile},#{dimensions},CAST(#{vector} AS"
                    + " public.vector)) ON CONFLICT(chunk_id,profile) DO NOTHING")
    void insert(@Param("task") EmbeddingTaskEntity task, String vector, int dimensions);

    /**
     * 调用结果未知时冻结失败，不自动重发可能已计费请求。
     *
     * @param profile 当前模型空间
     * @return 超时任务数量
     */
    @Update(
            "UPDATE knowledge_embedding_task SET"
                + " state='FAILED',error_code='EMBEDDING_OUTCOME_UNKNOWN',lease_until=NULL,updated_at=now()"
                + " WHERE profile=#{profile} AND state='RUNNING' AND lease_until<now()")
    int expire(String profile);

    /**
     * 保留一次嵌入调用已知用量，未知结果为空，不记录正文和秘密。
     *
     * @param profile 向量空间
     * @param purpose INDEX或SEARCH
     * @param actor 搜索用户，索引为空
     * @param tokens 已知输入词元
     * @param error 可空错误码
     */
    @Insert(
            "INSERT INTO embedding_request_audit(profile,purpose,actor_id,input_tokens,error_code)"
                    + " VALUES(#{profile},#{purpose},#{actor},#{tokens},#{error})")
    void audit(String profile, String purpose, java.util.UUID actor, Long tokens, String error);
}
