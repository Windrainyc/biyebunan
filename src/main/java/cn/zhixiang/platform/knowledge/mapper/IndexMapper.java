package cn.zhixiang.platform.knowledge.mapper;

import cn.zhixiang.platform.knowledge.entity.IndexSourceEntity;
import cn.zhixiang.platform.knowledge.entity.IndexTaskEntity;

import org.apache.ibatis.annotations.*;

import java.util.*;

/** 可重建知识索引任务及内容代次屏障。 */
@Mapper
public interface IndexMapper {
    /**
     * 当前发布内容入队；调用方已锁内容，旧任务租约立即失效。
     *
     * @param job 新任务候选ID
     * @param content 内容
     */
    @Insert(
            "INSERT INTO knowledge_index_task(id,content_id,snapshot_id,content_generation) SELECT"
                + " #{job},id,published_version_id,index_generation FROM knowledge_content WHERE"
                + " id=#{content} ON CONFLICT(content_id) DO UPDATE SET"
                + " snapshot_id=excluded.snapshot_id,content_generation=excluded.content_generation,state='QUEUED',attempts=0,generation=knowledge_index_task.generation+1,version=knowledge_index_task.version+1,lease_until=NULL,error_code=NULL,next_attempt_at=now(),updated_at=now()")
    void enqueue(UUID job, UUID content);

    /**
     * 当前任务ID。
     *
     * @param content 内容
     * @return ID
     */
    @Select("SELECT id FROM knowledge_index_task WHERE content_id=#{content}")
    UUID jobId(UUID content);

    /**
     * 重建索引使旧代次失效；不修改正文。
     *
     * @param content 内容
     * @param actor 操作者
     */
    @Update(
            "UPDATE knowledge_content SET"
                + " index_generation=index_generation+1,index_state='PENDING',version=version+1,updated_at=now(),updated_by=#{actor}"
                + " WHERE id=#{content}")
    void invalidate(UUID content, UUID actor);

    /**
     * 原子领取。
     *
     * @return 任务或null
     */
    @Select(
            value =
                    "WITH p AS (SELECT id FROM knowledge_index_task WHERE (state='QUEUED' AND"
                        + " next_attempt_at<=clock_timestamp()) OR (state='RUNNING' AND"
                        + " lease_until<clock_timestamp()) ORDER BY next_attempt_at,id FOR UPDATE"
                        + " SKIP LOCKED LIMIT 1) UPDATE knowledge_index_task t SET"
                        + " state='RUNNING',attempts=attempts+1,generation=generation+1,version=version+1,lease_until=clock_timestamp()+interval"
                        + " '5 minutes',updated_at=now() FROM p WHERE t.id=p.id RETURNING"
                        + " t.id,t.content_id,t.snapshot_id,t.content_generation,t.generation,t.attempts",
            affectData = true)
    IndexTaskEntity claim();

    /**
     * 获取不可变Markdown或元信息；非Markdown当前明确不可抽取。
     *
     * @param id 快照
     * @return 内容
     */
    @Select("SELECT title,type,markdown FROM knowledge_version WHERE id=#{id}")
    IndexSourceEntity source(UUID id);

    /**
     * 提交任务结果，必须是本代次未过期租约。
     *
     * @param id 任务
     * @param generation 租约
     * @param state 终态
     * @param error 错误码
     * @return 更新数
     */
    @Update(
            "UPDATE knowledge_index_task SET"
                + " state=#{state},error_code=#{error},lease_until=NULL,version=version+1,updated_at=now()"
                + " WHERE id=#{id} AND generation=#{generation} AND state='RUNNING' AND"
                + " lease_until>clock_timestamp()")
    int finish(UUID id, long generation, String state, String error);

    /**
     * 清理旧索引，历史正文快照不删除。
     *
     * @param content 内容
     */
    @Delete("DELETE FROM knowledge_chunk WHERE content_id=#{content}")
    void clear(UUID content);

    /**
     * 插入一片可引用文本。
     *
     * @param id 分片ID
     * @param t 任务
     * @param ordinal 序号，从1开始
     * @param body 文本
     */
    @Insert(
            "INSERT INTO knowledge_chunk(id,content_id,version_id,generation,ordinal,body)"
                + " VALUES(#{id},#{t.contentId},#{t.snapshotId},#{t.contentGeneration},#{ordinal},#{body})")
    void chunk(UUID id, @Param("t") IndexTaskEntity t, int ordinal, String body);

    /**
     * 记录索引实际状态，不改变正文版本。
     *
     * @param content 内容
     * @param generation 内容代次
     * @param state 索引状态
     */
    @Update(
            "UPDATE knowledge_content SET index_state=#{state} WHERE id=#{content} AND"
                    + " index_generation=#{generation}")
    void state(UUID content, long generation, String state);
}
