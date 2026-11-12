package cn.zhixiang.platform.chat.mapper;

import cn.zhixiang.platform.chat.dto.ConversationDtos;
import cn.zhixiang.platform.chat.entity.ConversationEntity;

import org.apache.ibatis.annotations.*;

import java.util.*;

/** 私聊查询在SQL层校验当前归属，不能只依赖会话创建者。 */
@Mapper
public interface ConversationMapper {
    /**
     * 检查会话中是否仍有运行中的回答；调用者先持会话锁以排除新任务。
     *
     * @param id 会话标识
     * @return 是否存在未终止运行
     */
    @Select(
            "SELECT EXISTS(SELECT 1 FROM generation_run WHERE conversation_id=#{id} AND state IN"
                    + " ('RESERVED','RUNNING'))")
    boolean running(UUID id);

    /** 元数据投影。 */
    String C = "c.id,c.version,c.created_at,c.updated_at,c.project_id,c.title,c.student_id";

    /** 有效订单范围。 */
    String ACCESS =
            "c.student_id=#{student} AND c.deleted_at IS NULL AND (c.project_id IS NULL OR"
                    + " EXISTS(SELECT 1 FROM software_project p JOIN software_order o ON"
                    + " o.id=p.order_id WHERE p.id=c.project_id AND o.student_id=#{student} AND"
                    + " o.status IN ('CONFIRMED','ACTIVE','COMPLETED','ARCHIVED')))";

    /**
     * 授权分页。
     *
     * @param q 条件
     * @param student 学生
     * @return 内部记录
     */
    @Select(
            "SELECT "
                    + C
                    + " FROM chat_conversation c WHERE "
                    + ACCESS
                    + " AND c.title ILIKE #{q.pattern} ORDER BY ${q.orderBy} LIMIT #{q.size} OFFSET"
                    + " #{q.offset}")
    List<ConversationEntity> list(@Param("q") ConversationDtos.Query q, UUID student);

    /**
     * 同范围计数。
     *
     * @param q 条件
     * @param student 学生
     * @return 数量
     */
    @Select(
            "SELECT count(*) FROM chat_conversation c WHERE "
                    + ACCESS
                    + " AND c.title ILIKE #{q.pattern}")
    long count(@Param("q") ConversationDtos.Query q, UUID student);

    /**
     * 授权详情。
     *
     * @param id 会话
     * @param student 学生
     * @return 记录或null
     */
    @Select("SELECT " + C + " FROM chat_conversation c WHERE c.id=#{id} AND " + ACCESS)
    ConversationEntity get(UUID id, UUID student);

    /**
     * 锁定授权会话，写入调用方先持订单锁。
     *
     * @param id 会话
     * @param student 学生
     * @return 记录或null
     */
    @Select(
            "SELECT "
                    + C
                    + " FROM chat_conversation c WHERE c.id=#{id} AND "
                    + ACCESS
                    + " FOR UPDATE")
    ConversationEntity lock(UUID id, UUID student);

    /**
     * 新建固定归属会话。
     *
     * @param id 新ID
     * @param w 输入
     * @param student 服务端身份
     */
    @Insert(
            "INSERT INTO chat_conversation(id,project_id,student_id,title)"
                    + " VALUES(#{id},#{w.projectId},#{student},#{w.title})")
    void insert(UUID id, @Param("w") ConversationDtos.Write w, UUID student);

    /**
     * CAS隐藏会话。
     *
     * @param id ID
     * @param version 版本
     * @return 更新数量
     */
    @Update(
            "UPDATE chat_conversation SET deleted_at=now(),version=version+1,updated_at=now() WHERE"
                    + " id=#{id} AND version=#{version} AND deleted_at IS NULL")
    int delete(UUID id, long version);
}
