package cn.zhixiang.platform.chat.mapper;

import cn.zhixiang.platform.chat.entity.MessageEntity;
import cn.zhixiang.platform.chat.service.RunSourceAccess;
import cn.zhixiang.platform.shared.dto.PageQuery;

import org.apache.ibatis.annotations.*;

import java.util.*;

/** 消息持久层；写入绑定运行固有会话，读取由上层先校验会话归属。 */
@Mapper
public interface MessageMapper {
    /**
     * 按运行角色读取唯一消息。
     *
     * @param run 运行
     * @param role USER/ASSISTANT
     * @return 记录或null
     */
    @Select(
            "SELECT id,conversation_id,run_id,role,text,citations::text AS citations,created_at"
                    + " FROM chat_message WHERE run_id=#{run} AND role=#{role}")
    MessageEntity existing(UUID run, String role);

    /**
     * 在运行锁内保存消息；会话由数据库运行记录决定。
     *
     * @param id 消息ID
     * @param run 运行
     * @param role 角色
     * @param text 正文
     */
    @Insert(
            "INSERT INTO chat_message(id,conversation_id,run_id,role,text,citations) SELECT"
                + " #{id},conversation_id,id,#{role},#{text},CASE WHEN #{role}='ASSISTANT' THEN"
                + " (SELECT"
                + " coalesce(jsonb_agg(jsonb_build_object('sourceType','KNOWLEDGE','versionId',version_id,'chunkId',chunk_id,'page',page,'quote',quote)"
                + " ORDER BY page,chunk_id),'[]'::jsonb) FROM run_source WHERE run_id=#{run}) ELSE"
                + " '[]'::jsonb END FROM generation_run WHERE id=#{run}")
    void insert(UUID id, UUID run, String role, String text);

    /**
     * 稳定分页；消息不可更新，updatedAt排序映射到创建时间。
     *
     * @param conversation 已授权会话
     * @param q 查询
     * @param student 当前学生
     * @return 内部记录
     */
    @Select(
            "SELECT id,conversation_id,run_id,role,text,citations::text AS citations,created_at"
                    + " FROM (SELECT *,created_at AS updated_at FROM chat_message WHERE"
                    + " conversation_id=#{conversation} AND text ILIKE #{q.pattern}) m WHERE NOT ("
                    + RunSourceAccess.DENIED
                    + ") ORDER BY ${q.orderBy} LIMIT #{q.size} OFFSET #{q.offset}")
    List<MessageEntity> list(UUID conversation, @Param("q") PageQuery q, UUID student);

    /**
     * 同过滤计数。
     *
     * @param conversation 会话
     * @param q 查询
     * @param student 当前学生
     * @return 数量
     */
    @Select(
            "SELECT count(*) FROM chat_message m WHERE conversation_id=#{conversation} AND text"
                    + " ILIKE #{q.pattern} AND NOT ("
                    + RunSourceAccess.DENIED
                    + ")")
    long count(UUID conversation, @Param("q") PageQuery q, UUID student);
}
