package cn.zhixiang.platform.project.mapper;

import cn.zhixiang.platform.project.dto.DeliveryDtos;
import cn.zhixiang.platform.project.entity.DeliveryEntity;

import org.apache.ibatis.annotations.*;

import java.util.*;

/** 交付快照端口；调用者写入前持有项目锁。 */
@Mapper
public interface DeliveryMapper {
    /** 内部投影。 */
    String C =
            "id,version,created_at,updated_at,kind,asset_id,note,project_id,version_number,state,release_id,release_number";

    /** 共用列表过滤，学生只能读取当前发布版。 */
    String F =
            " FROM project_delivery WHERE project_id=#{project} AND (note ILIKE #{q.pattern} OR"
                    + " kind ILIKE #{q.pattern}) <if test='student'>AND state='PUBLISHED'</if><if"
                    + " test='q.status != null'> AND state=#{q.status}</if>";

    /**
     * 分页查询。
     *
     * @param project 项目
     * @param q 条件
     * @param student 是否学生
     * @return 记录
     */
    @Select(
            "<script>SELECT "
                    + C
                    + F
                    + " ORDER BY ${q.orderBy} LIMIT #{q.size} OFFSET #{q.offset}</script>")
    List<DeliveryEntity> list(UUID project, @Param("q") DeliveryDtos.Query q, boolean student);

    /**
     * 同范围计数。
     *
     * @param project 项目
     * @param q 条件
     * @param student 学生范围
     * @return 数量
     */
    @Select("<script>SELECT count(*)" + F + "</script>")
    long count(UUID project, @Param("q") DeliveryDtos.Query q, boolean student);

    /**
     * 读取内部快照。
     *
     * @param id ID
     * @return 行或null
     */
    @Select("SELECT " + C + " FROM project_delivery WHERE id=#{id}")
    DeliveryEntity get(UUID id);

    /**
     * 创建新序号；项目锁防止MAX竞争，唯一约束最终兜底。
     *
     * @param id ID
     * @param project 项目
     * @param w 内容
     * @param actor 操作者
     */
    @Insert(
            "INSERT INTO"
                + " project_delivery(id,project_id,kind,asset_id,note,version_number,created_by,updated_by)"
                + " SELECT"
                + " #{id},#{project},#{w.kind},#{w.assetId},#{w.note},coalesce(max(version_number),0)+1,#{actor},#{actor}"
                + " FROM project_delivery WHERE project_id=#{project} AND kind=#{w.kind}")
    void insert(UUID id, UUID project, @Param("w") DeliveryDtos.Write w, UUID actor);

    /**
     * 是否存在更晚创建的同类版本，防止旧草稿覆盖新版本。
     *
     * @param project 项目
     * @param kind 类别
     * @param number 序号
     * @return 是否存在
     */
    @Select(
            "SELECT EXISTS(SELECT 1 FROM project_delivery WHERE project_id=#{project} AND"
                    + " kind=#{kind} AND version_number>#{number})")
    boolean newer(UUID project, String kind, int number);

    /**
     * 将旧当前版改为历史版，内容不变。
     *
     * @param project 项目
     * @param kind 类别
     * @param actor 操作者
     */
    @Update(
            "UPDATE project_delivery SET"
                + " state='SUPERSEDED',version=version+1,updated_at=now(),updated_by=#{actor} WHERE"
                + " project_id=#{project} AND kind=#{kind} AND state='PUBLISHED'")
    void supersede(UUID project, String kind, UUID actor);

    /**
     * 更新状态，不修改内容。
     *
     * @param id 交付
     * @param state 状态
     * @param actor 操作者
     */
    @Update(
            "UPDATE project_delivery SET"
                    + " state=#{state},version=version+1,updated_at=now(),updated_by=#{actor} WHERE"
                    + " id=#{id}")
    void state(UUID id, String state, UUID actor);

    /**
     * 保存发布或撤回依据。
     *
     * @param id 事件
     * @param delivery 交付
     * @param action 动作
     * @param reason 依据
     * @param actor 操作者
     */
    @Insert(
            "INSERT INTO delivery_event(id,delivery_id,action,reason,actor_id)"
                    + " VALUES(#{id},#{delivery},#{action},#{reason},#{actor})")
    void event(UUID id, UUID delivery, String action, String reason, UUID actor);

    /**
     * 将同次发布的不可变文件归入一个版本。
     *
     * @param id 文件交付
     * @param batch 批次
     * @param number 项目整体版本号
     */
    @Update(
            "UPDATE project_delivery SET release_id=#{batch},release_number=#{number} WHERE"
                    + " id=#{id} AND release_id IS NULL")
    void release(UUID id, UUID batch, int number);

    /**
     * 在项目锁内计算下一个整批版本号。
     *
     * @param project 项目
     * @return 下一序号
     */
    @Select(
            "SELECT coalesce(max(release_number),0)+1 FROM project_delivery WHERE"
                    + " project_id=#{project}")
    int nextRelease(UUID project);
}
