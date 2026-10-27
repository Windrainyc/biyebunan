package cn.zhixiang.platform.project.mapper;

import cn.zhixiang.platform.identity.model.Actor;
import cn.zhixiang.platform.project.dto.ProjectDtos;
import cn.zhixiang.platform.project.entity.ProjectEntity;

import org.apache.ibatis.annotations.*;

import java.time.*;
import java.util.*;

/** 项目数据端口，查询在数据库执行订单归属范围。 */
@Mapper
public interface ProjectMapper {
    /**
     * 范围内分页。
     *
     * @param q 查询
     * @param actor 服务端身份
     * @return 记录
     */
    @Select(
            "<script>SELECT"
                + " id,version,created_at,updated_at,order_id,title,requirements,delivery_date,stage,COALESCE((SELECT"
                + " sum(weight) FROM project_milestone WHERE project_id=software_project.id AND"
                + " completed),0)::int AS progress FROM software_project WHERE EXISTS(SELECT 1 FROM"
                + " software_order o WHERE o.id=software_project.order_id AND"
                + " (#{actor.platform}='admin' OR (#{actor.platform}='student' AND"
                + " o.student_id=#{actor.id} AND o.status IN"
                + " ('CONFIRMED','ACTIVE','COMPLETED','ARCHIVED')) OR (#{actor.platform}='partner'"
                + " AND o.partner_id=#{actor.id}))) AND title ILIKE #{q.pattern} <if test='q.status"
                + " != null'>AND stage=#{q.status}</if> ORDER BY ${q.orderBy} LIMIT #{q.size}"
                + " OFFSET #{q.offset}</script>")
    List<ProjectEntity> list(@Param("q") ProjectDtos.Query q, @Param("actor") Actor actor);

    /**
     * 范围内总数。
     *
     * @param q 查询
     * @param actor 身份
     * @return 总数
     */
    @Select(
            "<script>SELECT count(*) FROM software_project WHERE EXISTS(SELECT 1 FROM"
                + " software_order o WHERE o.id=software_project.order_id AND"
                + " (#{actor.platform}='admin' OR (#{actor.platform}='student' AND"
                + " o.student_id=#{actor.id} AND o.status IN"
                + " ('CONFIRMED','ACTIVE','COMPLETED','ARCHIVED')) OR (#{actor.platform}='partner'"
                + " AND o.partner_id=#{actor.id}))) AND title ILIKE #{q.pattern} <if test='q.status"
                + " != null'>AND stage=#{q.status}</if></script>")
    long count(@Param("q") ProjectDtos.Query q, @Param("actor") Actor actor);

    /**
     * 授权查询。
     *
     * @param id 项目
     * @param actor 身份
     * @return 记录或null
     */
    @Select(
            "SELECT"
                + " id,version,created_at,updated_at,order_id,title,requirements,delivery_date,stage,COALESCE((SELECT"
                + " sum(weight) FROM project_milestone WHERE project_id=software_project.id AND"
                + " completed),0)::int AS progress FROM software_project WHERE id=#{id} AND"
                + " EXISTS(SELECT 1 FROM software_order o WHERE o.id=software_project.order_id AND"
                + " (#{actor.platform}='admin' OR (#{actor.platform}='student' AND"
                + " o.student_id=#{actor.id} AND o.status IN"
                + " ('CONFIRMED','ACTIVE','COMPLETED','ARCHIVED')) OR (#{actor.platform}='partner'"
                + " AND o.partner_id=#{actor.id})))")
    ProjectEntity get(UUID id, @Param("actor") Actor actor);

    /**
     * 锁项目，调用前必须已锁定所属订单。
     *
     * @param id 项目
     * @return 行或null
     */
    @Select(
            "SELECT"
                + " id,version,created_at,updated_at,order_id,title,requirements,delivery_date,stage,COALESCE((SELECT"
                + " sum(weight) FROM project_milestone WHERE project_id=software_project.id AND"
                + " completed),0)::int AS progress FROM software_project WHERE id=#{id} FOR UPDATE")
    ProjectEntity lock(UUID id);

    /**
     * 新建项目。
     *
     * @param id 新ID
     * @param w 输入
     * @param actor 操作者
     */
    @Insert(
            "INSERT INTO"
                + " software_project(id,order_id,title,requirements,delivery_date,created_by,updated_by)"
                + " VALUES(#{id},#{w.orderId},#{w.title},#{w.requirements},#{w.deliveryDate},#{actor},#{actor})")
    void insert(UUID id, @Param("w") ProjectDtos.Write w, UUID actor);

    /**
     * 修改主体，不允许覆盖状态和进度。
     *
     * @param id 项目
     * @param version CAS
     * @param w 输入
     * @param actor 操作者
     * @return 修改行数
     */
    @Update(
            "UPDATE software_project SET"
                + " title=#{w.title},requirements=#{w.requirements},delivery_date=#{w.deliveryDate},version=version+1,updated_at=now(),updated_by=#{actor}"
                + " WHERE id=#{id} AND version=#{version}")
    int update(UUID id, long version, @Param("w") ProjectDtos.Write w, UUID actor);

    /**
     * 检查调整交付日是否早于已有里程碑，调用方已锁项目。
     *
     * @param id 项目
     * @param date 新交付日期
     * @return 是否存在更晚的里程碑
     */
    @Select(
            "SELECT EXISTS(SELECT 1 FROM project_milestone WHERE project_id=#{id} AND"
                    + " due_date>#{date})")
    boolean lateMilestone(UUID id, LocalDate date);
}
