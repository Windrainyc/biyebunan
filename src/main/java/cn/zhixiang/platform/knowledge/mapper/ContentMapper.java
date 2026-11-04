package cn.zhixiang.platform.knowledge.mapper;

import cn.zhixiang.platform.knowledge.dto.ContentDtos;
import cn.zhixiang.platform.knowledge.entity.ContentEntity;

import org.apache.ibatis.annotations.*;

import java.util.*;

/** 知识授权过滤必须在SQL分页和计数前完成；PUBLIC仍需已登录学生身份。 */
@Mapper
public interface ContentMapper {
    /** 当前范围授权，供详情/附件/索引复用同一规则。 */
    String ACCESS =
            "(c.audience='PUBLIC' OR (c.audience='MEMBER' AND EXISTS(SELECT 1 FROM membership m"
                    + " WHERE m.user_id=#{student} AND m.revoked_at IS NULL AND"
                    + " CURRENT_TIMESTAMP>=m.starts_at AND m.expires_at>CURRENT_TIMESTAMP)) OR"
                    + " (c.audience='PROJECT' AND EXISTS(SELECT 1 FROM software_project p JOIN"
                    + " software_order o ON o.id=p.order_id WHERE p.id=c.project_id AND"
                    + " o.student_id=#{student} AND o.status IN"
                    + " ('CONFIRMED','ACTIVE','COMPLETED','ARCHIVED'))))";

    /** 内部投影列。 */
    String C =
            "c.id,c.version,c.created_at,c.updated_at,c.title,c.category_id,c.type,c.asset_id,c.markdown,c.audience,c.project_id,c.state,c.published_version_id,c.index_state,c.index_generation,c.cover_asset_id";

    /** 共用分页条件。 */
    String F =
            " FROM knowledge_content c WHERE c.deleted_at IS NULL AND (#{admin} OR"
                    + " (c.state='PUBLISHED' AND "
                    + ACCESS
                    + ")) AND c.title ILIKE #{q.pattern} <if test='q.status != null'>AND"
                    + " c.state=#{q.status}</if><if test='q.categoryId != null'> AND"
                    + " c.category_id=#{q.categoryId}</if><if test='q.tagId != null'> AND"
                    + " EXISTS(SELECT 1 FROM knowledge_content_tag t WHERE t.content_id=c.id AND"
                    + " t.tag_id=#{q.tagId})</if><if test='q.contentType != null'> AND"
                    + " c.type=#{q.contentType}</if>";

    /**
     * 查询内容。
     *
     * @param id ID
     * @param admin 管理员范围
     * @param student 当前学生
     * @return 授权记录或null
     */
    @Select(
            "SELECT "
                    + C
                    + " FROM knowledge_content c WHERE c.id=#{id} AND c.deleted_at IS NULL AND"
                    + " (#{admin} OR (c.state='PUBLISHED' AND "
                    + ACCESS
                    + "))")
    ContentEntity get(UUID id, boolean admin, UUID student);

    /**
     * 锁定尚未删除内容。
     *
     * @param id ID
     * @return 行或null
     */
    @Select(
            "SELECT "
                    + C
                    + " FROM knowledge_content c WHERE c.id=#{id} AND c.deleted_at IS NULL FOR"
                    + " UPDATE")
    ContentEntity lock(UUID id);

    /**
     * 列表。
     *
     * @param q 条件
     * @param admin 管理员
     * @param student 当前学生
     * @return 行列表
     */
    @Select(
            "<script>SELECT "
                    + C
                    + F
                    + " ORDER BY ${q.orderBy} LIMIT #{q.size} OFFSET #{q.offset}</script>")
    List<ContentEntity> list(@Param("q") ContentDtos.Query q, boolean admin, UUID student);

    /**
     * 同范围计数。
     *
     * @param q 条件
     * @param admin 管理员
     * @param student 学生
     * @return 总数
     */
    @Select("<script>SELECT count(*)" + F + "</script>")
    long count(@Param("q") ContentDtos.Query q, boolean admin, UUID student);

    /**
     * 当前标签。
     *
     * @param id 内容
     * @return 排序标签ID
     */
    @Select("SELECT tag_id FROM knowledge_content_tag WHERE content_id=#{id} ORDER BY tag_id")
    List<UUID> tags(UUID id);

    /**
     * 新建草稿。
     *
     * @param id ID
     * @param w 输入
     * @param actor 操作者
     */
    @Insert(
            "INSERT INTO"
                + " knowledge_content(id,title,category_id,type,asset_id,cover_asset_id,markdown,audience,project_id,created_by,updated_by)"
                + " VALUES(#{id},#{w.title},#{w.categoryId},#{w.type},#{w.assetId},#{w.coverAssetId},#{w.markdown},#{w.audience},#{w.projectId},#{actor},#{actor})")
    void insert(UUID id, @Param("w") ContentDtos.Write w, UUID actor);

    /**
     * 更新可编辑草稿并撤销审核状态。
     *
     * @param id ID
     * @param w 输入
     * @param actor 操作者
     */
    @Update(
            "UPDATE knowledge_content SET"
                + " title=#{w.title},category_id=#{w.categoryId},type=#{w.type},asset_id=#{w.assetId},cover_asset_id=#{w.coverAssetId},markdown=#{w.markdown},audience=#{w.audience},project_id=#{w.projectId},state='DRAFT',index_state='PENDING',index_generation=index_generation+1,version=version+1,updated_at=now(),updated_by=#{actor}"
                + " WHERE id=#{id}")
    void update(UUID id, @Param("w") ContentDtos.Write w, UUID actor);

    /**
     * 替换关联前清除当前标签，快照关联不会删除。
     *
     * @param id 内容
     */
    @Delete("DELETE FROM knowledge_content_tag WHERE content_id=#{id}")
    void clearTags(UUID id);

    /**
     * 写标签引用。
     *
     * @param id 内容
     * @param tag 标签
     */
    @Insert("INSERT INTO knowledge_content_tag(content_id,tag_id) VALUES(#{id},#{tag})")
    void tag(UUID id, UUID tag);

    /**
     * 标记业务删除，保留历史与引用。
     *
     * @param id ID
     * @param actor 操作者
     */
    @Update(
            "UPDATE knowledge_content SET"
                + " deleted_at=now(),state='WITHDRAWN',index_generation=index_generation+1,index_state='PENDING',version=version+1,updated_at=now(),updated_by=#{actor}"
                + " WHERE id=#{id}")
    void delete(UUID id, UUID actor);

    /**
     * 更改审核或撤回状态，失效旧索引。
     *
     * @param id ID
     * @param state 状态
     * @param actor 操作者
     */
    @Update(
            "UPDATE knowledge_content SET"
                + " state=#{state},index_generation=index_generation+1,index_state='PENDING',version=version+1,updated_at=now(),updated_by=#{actor}"
                + " WHERE id=#{id}")
    void state(UUID id, String state, UUID actor);

    /**
     * 保存发布快照，编号在内容锁内分配。
     *
     * @param versionId 快照ID
     * @param id 内容
     * @param actor 操作者
     */
    @Insert(
            "INSERT INTO"
                + " knowledge_version(id,content_id,version_number,title,category_id,type,asset_id,cover_asset_id,markdown,audience,project_id,created_by)"
                + " SELECT #{versionId},c.id,(SELECT coalesce(max(version_number),0)+1 FROM"
                + " knowledge_version WHERE"
                + " content_id=c.id),c.title,c.category_id,c.type,c.asset_id,c.cover_asset_id,c.markdown,c.audience,c.project_id,#{actor}"
                + " FROM knowledge_content c WHERE c.id=#{id}")
    void snapshot(UUID versionId, UUID id, UUID actor);

    /**
     * 快照标签引用。
     *
     * @param versionId 快照
     * @param id 内容
     */
    @Insert(
            "INSERT INTO knowledge_version_tag(version_id,tag_id) SELECT #{versionId},tag_id FROM"
                    + " knowledge_content_tag WHERE content_id=#{id}")
    void snapshotTags(UUID versionId, UUID id);

    /**
     * 发布快照并失效旧索引。
     *
     * @param id 内容
     * @param versionId 快照
     * @param actor 操作者
     */
    @Update(
            "UPDATE knowledge_content SET"
                + " state='PUBLISHED',published_version_id=#{versionId},index_generation=index_generation+1,index_state='PENDING',version=version+1,updated_at=now(),updated_by=#{actor}"
                + " WHERE id=#{id}")
    void publish(UUID id, UUID versionId, UUID actor);

    /**
     * 原因历史。
     *
     * @param id 事件
     * @param content 内容
     * @param action 动作
     * @param reason 原因
     * @param actor 操作者
     */
    @Insert(
            "INSERT INTO knowledge_event(id,content_id,action,reason,actor_id)"
                    + " VALUES(#{id},#{content},#{action},#{reason},#{actor})")
    void event(UUID id, UUID content, String action, String reason, UUID actor);

    /**
     * 管理员内容处理历史，按时间和标识稳定倒序。
     *
     * @param id 内容标识
     * @param size 页大小
     * @param offset 偏移行数
     * @return 历史行
     */
    @Select(
            "SELECT e.id,e.action,e.reason,u.display_name actor_name,e.created_at FROM"
                + " knowledge_event e JOIN user_account u ON u.id=e.actor_id WHERE"
                + " e.content_id=#{id} ORDER BY e.created_at DESC,e.id DESC LIMIT #{size} OFFSET"
                + " #{offset}")
    java.util.List<cn.zhixiang.platform.knowledge.entity.ContentEventEntity> history(
            UUID id, int size, int offset);

    /**
     * 内容历史总量。
     *
     * @param id 内容标识
     * @return 条数
     */
    @Select("SELECT count(*) FROM knowledge_event WHERE content_id=#{id}")
    long historyCount(UUID id);
}
