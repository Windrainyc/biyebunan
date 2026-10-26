package cn.zhixiang.platform.chat;
import java.util.*;
import org.apache.ibatis.annotations.*;
import cn.zhixiang.platform.shared.PageQuery;
/** 私聊审计专用查询；只有chat:audit用例调用，不复用中介或普通学生范围。 */
@Mapper
public interface ChatAuditMapper {
 /** 会话目录含用户隐藏的记录，隐藏不是审计删除。
  * @param q 标题过滤与分页
  * @return 会话投影 */
 @Select("SELECT "+ConversationMapper.C+" FROM chat_conversation c WHERE title ILIKE #{q.pattern} ORDER BY ${q.orderBy} LIMIT #{q.size} OFFSET #{q.offset}") List<ConversationDtos.View> conversations(@Param("q") PageQuery q);
 /** 目录总数。
  * @param q 同一过滤
  * @return 总数 */
 @Select("SELECT count(*) FROM chat_conversation WHERE title ILIKE #{q.pattern}") long conversationCount(@Param("q") PageQuery q);
 /** 会话存在性。
  * @param id 会话
  * @return 是否存在 */
 @Select("SELECT EXISTS(SELECT 1 FROM chat_conversation WHERE id=#{id})") boolean exists(UUID id);
 /** 审计历史，含已撤回引用的原始证据；学生读取仍独立过滤。
  * @param id 会话
  * @param q 正文过滤与分页
  * @return 消息内部行 */
 @Select("SELECT id,conversation_id,run_id,role,text,citations::text AS citations,created_at FROM (SELECT *,created_at AS updated_at FROM chat_message WHERE conversation_id=#{id} AND text ILIKE #{q.pattern}) m ORDER BY ${q.orderBy} LIMIT #{q.size} OFFSET #{q.offset}") List<MessageMapper.Row> messages(UUID id,@Param("q") PageQuery q);
 /** 历史总数。
  * @param id 会话
  * @param q 同过滤
  * @return 总数 */
 @Select("SELECT count(*) FROM chat_message WHERE conversation_id=#{id} AND text ILIKE #{q.pattern}") long messageCount(UUID id,@Param("q") PageQuery q);
}
