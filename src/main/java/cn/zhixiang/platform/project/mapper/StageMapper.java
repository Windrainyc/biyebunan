package cn.zhixiang.platform.project.mapper;

import org.apache.ibatis.annotations.*;

import java.util.UUID;

/** 阶段迁移持久层，所有写入在订单与项目锁下执行。 */
@Mapper
public interface StageMapper {
    /**
     * 原暂停阶段。
     *
     * @param id 项目
     * @return 阶段或null
     */
    @Select("SELECT paused_from FROM software_project WHERE id=#{id}")
    String pausedFrom(UUID id);

    /**
     * 校验验收最低交付门槛；当前要求源码及论文模板已发布。
     *
     * @param id 项目
     * @return 是否满足
     */
    @Select(
            "SELECT count(DISTINCT kind)=2 FROM project_delivery WHERE project_id=#{id} AND kind IN"
                    + " ('SOURCE_CODE','THESIS_TEMPLATE') AND state='PUBLISHED'")
    boolean deliverable(UUID id);

    /**
     * CAS变更阶段并保存暂停来源。
     *
     * @param id 项目
     * @param version 版本
     * @param stage 目标
     * @param paused 原暂停阶段
     * @param actor 身份
     * @return 修改行数
     */
    @Update(
            "UPDATE software_project SET"
                + " stage=#{stage},paused_from=#{paused},version=version+1,updated_by=#{actor},updated_at=now()"
                + " WHERE id=#{id} AND version=#{version}")
    int change(UUID id, long version, String stage, String paused, UUID actor);

    /**
     * 保存不可覆盖的阶段历史。
     *
     * @param id 事件
     * @param project 项目
     * @param from 原阶段
     * @param to 目标阶段
     * @param reason 依据
     * @param actor 操作者
     * @param decision 学生决定，可空
     */
    @Insert(
            "INSERT INTO"
                + " project_stage_event(id,project_id,from_stage,to_stage,reason,actor_id,student_decision)"
                + " VALUES(#{id},#{project},#{from},#{to},#{reason},#{actor},#{decision})")
    void event(
            UUID id,
            UUID project,
            String from,
            String to,
            String reason,
            UUID actor,
            String decision);
}
