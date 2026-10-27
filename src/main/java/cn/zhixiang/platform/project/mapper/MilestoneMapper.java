package cn.zhixiang.platform.project.mapper;

import cn.zhixiang.platform.project.dto.MilestoneDtos;
import cn.zhixiang.platform.project.entity.MilestoneEntity;
import cn.zhixiang.platform.shared.dto.PageQuery;

import org.apache.ibatis.annotations.*;

import java.util.*;

/** 里程碑数据访问；动态排序仅来自 PageQuery 封闭枚举映射。 */
@Mapper
public interface MilestoneMapper {
    /**
     * 返回当前页持久化投影。
     *
     * @param projectId 项目范围
     * @param q 已校验分页条件
     * @return 当前页持久化投影
     */
    @Select(
            "<script>SELECT"
                + " id,version,created_at,updated_at,name,due_date,weight,completed,project_id FROM"
                + " project_milestone WHERE project_id=#{projectId} AND name ILIKE #{q.pattern}"
                + " ORDER BY ${q.orderBy} LIMIT #{q.size} OFFSET #{q.offset}</script>")
    List<MilestoneEntity> list(UUID projectId, @Param("q") PageQuery q);

    /**
     * 返回过滤后总数。
     *
     * @param projectId 项目范围
     * @param q 与列表一致的过滤条件
     * @return 过滤后总数
     */
    @Select(
            "<script>SELECT count(*) FROM project_milestone WHERE project_id=#{projectId} AND name"
                    + " ILIKE #{q.pattern}</script>")
    long count(UUID projectId, @Param("q") PageQuery q);

    /**
     * 返回资源或 null。
     *
     * @param id 标识
     * @return 资源或 null
     */
    @Select(
            "SELECT id,version,created_at,updated_at,name,due_date,weight,completed,project_id FROM"
                    + " project_milestone WHERE id=#{id}")
    MilestoneEntity get(UUID id);

    /**
     * 返回加锁资源；串行化引用创建、状态更改和删除。
     *
     * @param id 标识
     * @return 加锁资源；串行化引用创建、状态更改和删除
     */
    @Select(
            "SELECT id,version,created_at,updated_at,name,due_date,weight,completed,project_id FROM"
                    + " project_milestone WHERE id=#{id} FOR UPDATE")
    MilestoneEntity lock(UUID id);

    /**
     * 在调用方业务事务内插入记录，由数据库约束保证唯一性和引用完整性。
     *
     * @param projectId 所属项目
     * @param id 新标识
     * @param w 已验证写入值
     * @param actor 服务端操作者
     */
    @Insert(
            "INSERT INTO"
                + " project_milestone(id,name,due_date,weight,completed,project_id,created_by,updated_by)"
                + " VALUES(#{id},#{w.name},#{w.dueDate},#{w.weight},#{w.completed},#{projectId},#{actor},#{actor})")
    void insert(UUID projectId, UUID id, @Param("w") MilestoneDtos.Write w, UUID actor);

    /**
     * 返回CAS 命中数。
     *
     * @param id 标识
     * @param version 期望版本
     * @param w 新值
     * @param actor 服务端操作者
     * @return CAS 命中数
     */
    @Update(
            "UPDATE project_milestone SET"
                + " name=#{w.name},due_date=#{w.dueDate},weight=#{w.weight},completed=#{w.completed},version=version+1,updated_at=now(),updated_by=#{actor}"
                + " WHERE id=#{id} AND version=#{version}")
    int update(UUID id, long version, @Param("w") MilestoneDtos.Write w, UUID actor);

    /**
     * 返回删除数量；引用由数据库外键保护。
     *
     * @param id 标识
     * @param version 期望版本
     * @return 删除数量；引用由数据库外键保护
     */
    @Delete("DELETE FROM project_milestone WHERE id=#{id} AND version=#{version}")
    int delete(UUID id, long version);

    /**
     * 计算其余里程碑权重，调用方持项目锁。
     *
     * @param projectId 项目
     * @param exclude 修改时排除当前ID，创建传新ID
     * @return 权重总和
     */
    @Select(
            "SELECT COALESCE(sum(weight),0)::int FROM project_milestone WHERE"
                    + " project_id=#{projectId} AND id!=#{exclude}")
    int weight(UUID projectId, UUID exclude);

    /**
     * 更新父项目版本，使进度变化也可被CAS感知。
     *
     * @param projectId 项目
     * @param actor 操作者
     */
    @Update(
            "UPDATE software_project SET version=version+1,updated_at=now(),updated_by=#{actor}"
                    + " WHERE id=#{projectId}")
    void touch(UUID projectId, UUID actor);
}
