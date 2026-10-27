package cn.zhixiang.platform.chat.mapper;

import cn.zhixiang.platform.chat.dto.RunDtos;
import cn.zhixiang.platform.chat.entity.RunContext;
import cn.zhixiang.platform.chat.entity.RunExistingEntity;
import cn.zhixiang.platform.chat.entity.RunHistoryEntity;
import cn.zhixiang.platform.chat.entity.RunSourceEntity;
import cn.zhixiang.platform.knowledge.mapper.ContentMapper;

import org.apache.ibatis.annotations.*;

import java.util.*;

/** 生成任务与固定上下文端口；权限不能依赖客户端role。 */
@Mapper
public interface RunMapper {
    /**
     * 查询永久客户端ID。
     *
     * @param student 学生
     * @param client 客户端ID
     * @return 内部记录或null
     */
    @Select(
            "SELECT id,conversation_id,request_hash FROM generation_run WHERE student_id=#{student}"
                    + " AND client_message_id=#{client}")
    RunExistingEntity existing(UUID student, UUID client);

    /**
     * 状态投影，创建与预占在同事务内完成后才可见。
     *
     * @param run 运行
     * @return 状态或null
     */
    @Select(
            "SELECT r.id,r.version,r.created_at,r.updated_at,r.conversation_id,r.state,h.state AS"
                    + " billing_state,r.model_id,r.last_sequence,r.error_code FROM generation_run r"
                    + " JOIN quota_hold h ON h.run_id=r.id WHERE r.id=#{run}")
    RunDtos.View view(UUID run);

    /**
     * 选择已生效启用价格；上层持模型锁。
     *
     * @param model 模型
     * @return 价格ID或null
     */
    @Select(
            "SELECT id FROM model_price WHERE model_id=#{model} AND status='ENABLED' AND"
                    + " effective_from<=clock_timestamp() ORDER BY effective_from DESC,id LIMIT 1")
    UUID price(UUID model);

    /**
     * 选择启用凭证；上层持模型锁。
     *
     * @param model 模型
     * @return 凭证ID或null
     */
    @Select(
            "SELECT id FROM model_key WHERE model_id=#{model} AND status='ENABLED' ORDER BY"
                    + " created_at,id LIMIT 1")
    UUID key(UUID model);

    /**
     * 创建永久幂等运行，唯一约束最终兜底。
     *
     * @param id 运行
     * @param student 学生
     * @param w 输入
     * @param hash 固定请求摘要
     * @param traceparent 创建请求父Span，可空
     * @param price 价格
     */
    @Insert(
            "INSERT INTO"
                + " generation_run(id,student_id,conversation_id,client_message_id,request_hash,model_id,price_id,traceparent)"
                + " VALUES(#{id},#{student},#{w.conversationId},#{w.clientMessageId},#{hash},#{w.modelId},#{price},#{traceparent})")
    void insert(
            UUID id,
            UUID student,
            @Param("w") RunDtos.Create w,
            String hash,
            UUID price,
            String traceparent);

    /**
     * 保存调用上下文。
     *
     * @param run 运行
     * @param key 凭证ID
     * @param code 模型代码
     * @param endpoint 完整端点
     * @param max 输出上限
     * @param messages 固定消息JSON
     */
    @Insert(
            "INSERT INTO run_context(run_id,key_id,model_code,endpoint,max_output_tokens,messages)"
                + " VALUES(#{run},#{key},#{code},#{endpoint},#{max},CAST(#{messages} AS jsonb))")
    void context(UUID run, UUID key, String code, String endpoint, int max, String messages);

    /**
     * 当前可用知识分片，不读取未索引或撤回内容。
     *
     * @param version 快照
     * @param student 学生
     * @return 最多4个已授权分片
     */
    @Select(
            "SELECT k.id AS chunk_id,k.version_id,k.body AS quote,k.ordinal+1 AS page FROM"
                    + " knowledge_chunk k JOIN knowledge_content c ON c.id=k.content_id WHERE"
                    + " k.version_id=#{version} AND c.published_version_id=k.version_id AND"
                    + " c.index_generation=k.generation AND c.index_state='READY' AND"
                    + " c.state='PUBLISHED' AND c.deleted_at IS NULL AND "
                    + ContentMapper.ACCESS
                    + " ORDER BY k.ordinal LIMIT 4")
    List<RunSourceEntity> sources(UUID version, UUID student);

    /**
     * 保存不可变引用。
     *
     * @param run 运行
     * @param s 引用
     */
    @Insert(
            "INSERT INTO run_source(run_id,version_id,chunk_id,quote,page)"
                    + " VALUES(#{run},#{s.versionId},#{s.chunkId},#{s.quote},#{s.page})")
    void source(UUID run, @Param("s") RunSourceEntity s);

    /**
     * 内部执行上下文，凭证仅内部解密。
     *
     * @param run 运行
     * @return 上下文或null
     */
    @Select(
            "SELECT c.run_id,c.model_code,c.endpoint,c.max_output_tokens,c.messages::text AS"
                + " messages,k.secret_ref,k.status FROM run_context c JOIN model_key k ON"
                + " k.id=c.key_id JOIN model_definition d ON d.id=k.model_id WHERE c.run_id=#{run}"
                + " AND d.status='ENABLED'")
    RunContext contextFor(UUID run);

    /**
     * 纯文本历史，只取无来源引用的成功运行，避免间接传播撤回的引用文本。
     *
     * @param conversation 会话
     * @return 最近20条，逆序，调用方恢复正序
     */
    @Select(
            "SELECT m.role,m.text AS content FROM chat_message m JOIN generation_run r ON"
                + " r.id=m.run_id WHERE m.conversation_id=#{conversation} AND r.state='SUCCEEDED'"
                + " AND NOT EXISTS(SELECT 1 FROM run_source s WHERE s.run_id=r.id) ORDER BY"
                + " m.created_at DESC,m.id DESC LIMIT 20")
    List<RunHistoryEntity> history(UUID conversation);

    /**
     * 执行时再查学生启停和当前订单归属，不依赖原HTTP会话仍在线。
     *
     * @param run 运行
     * @return 授权学生或null
     */
    @Select(
            "SELECT r.student_id FROM generation_run r JOIN chat_conversation c ON"
                + " c.id=r.conversation_id JOIN software_project p ON p.id=c.project_id JOIN"
                + " software_order o ON o.id=p.order_id JOIN user_account u ON u.id=r.student_id"
                + " WHERE r.id=#{run} AND c.deleted_at IS NULL AND c.student_id=r.student_id AND"
                + " o.student_id=r.student_id AND u.status='ENABLED' AND u.platform='student' AND"
                + " o.status IN ('CONFIRMED','ACTIVE','COMPLETED','ARCHIVED')")
    UUID executorStudent(UUID run);
}
