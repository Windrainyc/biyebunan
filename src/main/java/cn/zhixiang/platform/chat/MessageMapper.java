package cn.zhixiang.platform.chat;
import java.util.*;
import java.time.OffsetDateTime;
import org.apache.ibatis.annotations.*;
import cn.zhixiang.platform.shared.PageQuery;
/** 消息持久层；写入绑定运行固有会话，读取由上层先校验会话归属。 */
@Mapper
public interface MessageMapper {
 /** 按运行角色读取唯一消息。
 * @param run 运行
 * @param role USER/ASSISTANT
 * @return 记录或null */
 @Select("SELECT id,conversation_id,run_id,role,text,citations::text AS citations,created_at FROM chat_message WHERE run_id=#{run} AND role=#{role}") Row existing(UUID run,String role);
 /** 在运行锁内保存消息；会话由数据库运行记录决定。
 * @param id 消息ID
 * @param run 运行
 * @param role 角色
 * @param text 正文 */
 @Insert("INSERT INTO chat_message(id,conversation_id,run_id,role,text) SELECT #{id},conversation_id,id,#{role},#{text} FROM generation_run WHERE id=#{run}") void insert(UUID id,UUID run,String role,String text);
 /** 稳定分页；消息不可更新，updatedAt排序映射到创建时间。
 * @param conversation 已授权会话
 * @param q 查询
 * @return 内部记录 */
 @Select("SELECT id,conversation_id,run_id,role,text,citations::text AS citations,created_at FROM (SELECT *,created_at AS updated_at FROM chat_message WHERE conversation_id=#{conversation} AND text ILIKE #{q.pattern}) m ORDER BY ${q.orderBy} LIMIT #{q.size} OFFSET #{q.offset}") List<Row> list(UUID conversation,@Param("q") PageQuery q);
 /** 同过滤计数。
 * @param conversation 会话
 * @param q 查询
 * @return 数量 */
 @Select("SELECT count(*) FROM chat_message WHERE conversation_id=#{conversation} AND text ILIKE #{q.pattern}") long count(UUID conversation,@Param("q") PageQuery q);
 /** 内部行。
 * @param id 消息
 * @param conversationId 会话
 * @param runId 运行
 * @param role USER/ASSISTANT
 * @param text 正文
 * @param citations 引用JSON数组
 * @param createdAt 创建时间 */
 record Row(UUID id,UUID conversationId,UUID runId,String role,String text,String citations,OffsetDateTime createdAt){}
}
