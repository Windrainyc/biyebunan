package cn.zhixiang.platform.knowledge.mapper;

import cn.zhixiang.platform.knowledge.dto.ContentDtos;
import cn.zhixiang.platform.knowledge.entity.KnowledgeQueryHitRowEntity;
import cn.zhixiang.platform.knowledge.entity.KnowledgeQueryRevisionRowEntity;

import org.apache.ibatis.annotations.*;

import java.util.*;

/** 检索在内容状态、代次、快照和身份四个条件同时满足后返回。 */
@Mapper
public interface KnowledgeQueryMapper {
    /** 历史快照也按其原受众过滤，防止PUBLIC内容暴露过去PROJECT快照。 */
    String VACCESS =
            "(v.audience='PUBLIC' OR (v.audience='MEMBER' AND EXISTS(SELECT 1 FROM membership m"
                    + " WHERE m.user_id=#{student} AND m.revoked_at IS NULL AND"
                    + " CURRENT_TIMESTAMP>=m.starts_at AND m.expires_at>CURRENT_TIMESTAMP)) OR"
                    + " (v.audience='PROJECT' AND EXISTS(SELECT 1 FROM software_project p JOIN"
                    + " software_order o ON o.id=p.order_id WHERE p.id=v.project_id AND"
                    + " o.student_id=#{student} AND o.status IN"
                    + " ('CONFIRMED','ACTIVE','COMPLETED','ARCHIVED'))))";

    /** 历史共用过滤；先执行当前内容授权再执行历史范围。 */
    String VF =
            " FROM knowledge_version v JOIN knowledge_content c ON c.id=v.content_id WHERE"
                + " c.id=#{content} AND c.deleted_at IS NULL AND (#{admin} OR (c.state='PUBLISHED'"
                + " AND "
                    + ContentMapper.ACCESS
                    + " AND "
                    + VACCESS
                    + ")) AND v.title ILIKE #{q.pattern} <if test='q.status != null'>AND (CASE WHEN"
                    + " c.state='PUBLISHED' AND c.published_version_id=v.id THEN 'PUBLISHED' ELSE"
                    + " 'WITHDRAWN' END)=#{q.status}</if>";

    /**
     * 历史分页。
     *
     * @param content 内容
     * @param q 条件
     * @param admin 管理员
     * @param student 学生
     * @return 内部版本记录
     */
    @Select(
            "<script>SELECT * FROM (SELECT v.id,1::bigint AS version,v.created_at,v.created_at AS"
                    + " updated_at,v.content_id,v.version_number,v.asset_id,v.markdown,CASE WHEN"
                    + " c.state='PUBLISHED' AND c.published_version_id=v.id THEN 'PUBLISHED' ELSE"
                    + " 'WITHDRAWN' END AS state"
                    + VF
                    + ") x ORDER BY ${q.orderBy} LIMIT #{q.size} OFFSET #{q.offset}</script>")
    List<KnowledgeQueryRevisionRowEntity> versions(
            UUID content, @Param("q") ContentDtos.Query q, boolean admin, UUID student);

    /**
     * 历史计数。
     *
     * @param content 内容
     * @param q 条件
     * @param admin 管理员
     * @param student 学生
     * @return 数量
     */
    @Select("<script>SELECT count(*)" + VF + "</script>")
    long versionCount(UUID content, @Param("q") ContentDtos.Query q, boolean admin, UUID student);

    /** 检索匹配；限制为当前生成代次及当前发布快照。 */
    String SF =
            " FROM knowledge_content c JOIN knowledge_chunk k ON k.content_id=c.id AND"
                + " k.generation=c.index_generation AND k.version_id=c.published_version_id WHERE"
                + " c.deleted_at IS NULL AND c.state='PUBLISHED' AND c.index_state='READY' AND "
                    + ContentMapper.ACCESS
                    + " AND (k.body ILIKE #{q.pattern} OR c.title ILIKE #{q.pattern}) <if"
                    + " test='q.categoryId != null'>AND c.category_id=#{q.categoryId}</if><if"
                    + " test='q.tagId != null'> AND EXISTS(SELECT 1 FROM knowledge_content_tag ct"
                    + " WHERE ct.content_id=c.id AND ct.tag_id=#{q.tagId})</if><if"
                    + " test='q.contentType != null'> AND c.type=#{q.contentType}</if>";

    /**
     * 每内容返回一个可引用命中，稳定分页。
     *
     * @param q 过滤
     * @param student 当前学生
     * @return 命中
     */
    @Select(
            "<script>SELECT content_id,version_id,title,snippet,score,chunk_id,quote FROM (SELECT"
                + " DISTINCT ON (c.id) c.id,c.id AS"
                + " content_id,k.version_id,c.title,left(k.body,1000) AS snippet,CASE WHEN c.title"
                + " ILIKE #{q.pattern} THEN 2.0 ELSE 1.0 END::double precision AS score,k.id AS"
                + " chunk_id,k.body AS quote,c.created_at,c.updated_at"
                    + SF
                    + " ORDER BY c.id,k.ordinal) h ORDER BY ${q.orderBy} LIMIT #{q.size} OFFSET"
                    + " #{q.offset}</script>")
    List<KnowledgeQueryHitRowEntity> search(@Param("q") ContentDtos.Query q, UUID student);

    /**
     * 已授权匹配总数。
     *
     * @param q 条件
     * @param student 学生
     * @return 内容数
     */
    @Select("<script>SELECT count(DISTINCT c.id)" + SF + "</script>")
    long searchCount(@Param("q") ContentDtos.Query q, UUID student);

    /**
     * 问句关键词召回；发布代次与访问控制先过滤，结果按命中词数稳定排序。
     *
     * @param terms 安全词列表
     * @param student 学生身份
     * @return 最多三篇候选资料
     */
    @Select(
            "<script>SELECT content_id,version_id,title,snippet,score,chunk_id,quote FROM (SELECT"
                + " DISTINCT ON(c.id) c.id AS content_id,k.version_id,c.title,left(k.body,1000) AS"
                + " snippet,k.id AS chunk_id,k.body AS quote,(<foreach collection='terms' item='t'"
                + " separator=' + '>CASE WHEN position(#{t} in lower(c.title || ' ' || k.body))>0"
                + " THEN 1.0 ELSE 0.0 END</foreach>)::double precision AS score FROM"
                + " knowledge_content c JOIN knowledge_chunk k ON k.content_id=c.id AND"
                + " k.generation=c.index_generation AND k.version_id=c.published_version_id WHERE"
                + " c.deleted_at IS NULL AND c.state='PUBLISHED' AND c.index_state='READY' AND "
                    + ContentMapper.ACCESS
                    + " ORDER BY c.id,score DESC,k.ordinal) h WHERE score>0 ORDER BY score"
                    + " DESC,content_id LIMIT 3</script>")
    List<KnowledgeQueryHitRowEntity> question(List<String> terms, UUID student);
}
