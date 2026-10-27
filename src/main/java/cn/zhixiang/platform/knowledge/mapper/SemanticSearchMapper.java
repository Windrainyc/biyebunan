package cn.zhixiang.platform.knowledge.mapper;

import cn.zhixiang.platform.knowledge.dto.ContentDtos;
import cn.zhixiang.platform.knowledge.entity.KnowledgeQueryHitRowEntity;

import org.apache.ibatis.annotations.*;

import java.util.List;
import java.util.UUID;

/** 精确余弦检索，查询内强制当前发布代次和学生权限，不先取全局TopK再过滤。 */
@Mapper
public interface SemanticSearchMapper {
    /** 同一过滤用于列表和计数；固定最低相似度0.2，避免返回完全相反向量。 */
    String FILTER =
            " FROM knowledge_vector v JOIN knowledge_chunk k ON k.id=v.chunk_id JOIN"
                + " knowledge_content c ON c.id=k.content_id WHERE v.profile=#{profile} AND"
                + " c.deleted_at IS NULL AND c.state='PUBLISHED' AND c.index_state='READY' AND"
                + " k.generation=c.index_generation AND k.version_id=c.published_version_id AND "
                    + ContentMapper.ACCESS
                    + " AND (1-(v.embedding OPERATOR(public.&lt;=>) CAST(#{vector} AS"
                    + " public.vector))) >= 0.2 <if test='q.categoryId != null'>AND"
                    + " c.category_id=#{q.categoryId}</if><if test='q.tagId != null'>AND"
                    + " EXISTS(SELECT 1 FROM knowledge_content_tag ct WHERE ct.content_id=c.id AND"
                    + " ct.tag_id=#{q.tagId})</if><if test='q.contentType != null'>AND"
                    + " c.type=#{q.contentType}</if>";

    /**
     * 每内容选择最相关片段，列表仍遵循现有白名单时间排序和稳定ID。
     *
     * @param q 已验证过滤分页
     * @param student 服务端学生
     * @param profile 模型空间
     * @param vector 查询向量
     * @return 可引用命中
     */
    @Select(
            "<script>SELECT content_id,version_id,title,snippet,score,chunk_id,quote FROM (SELECT"
                + " DISTINCT ON(c.id) c.id,c.id AS"
                + " content_id,k.version_id,c.title,left(k.body,1000) AS snippet,(1-(v.embedding"
                + " OPERATOR(public.&lt;=>) CAST(#{vector} AS public.vector)))::double precision AS"
                + " score,k.id AS chunk_id,k.body AS quote,c.created_at,c.updated_at"
                    + FILTER
                    + " ORDER BY c.id,score DESC,k.ordinal) h ORDER BY ${q.orderBy} LIMIT #{q.size}"
                    + " OFFSET #{q.offset}</script>")
    List<KnowledgeQueryHitRowEntity> search(
            @Param("q") ContentDtos.Query q, UUID student, String profile, String vector);

    /**
     * 与列表同快照统计当前授权内容总数。
     *
     * @param q 查询过滤
     * @param student 学生
     * @param profile 向量空间
     * @param vector 查询向量
     * @return 内容数量
     */
    @Select("<script>SELECT count(DISTINCT c.id)" + FILTER + "</script>")
    long count(@Param("q") ContentDtos.Query q, UUID student, String profile, String vector);
}
