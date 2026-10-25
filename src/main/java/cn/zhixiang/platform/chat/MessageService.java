package cn.zhixiang.platform.chat;
import java.util.*;
import java.time.OffsetDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import cn.zhixiang.platform.identity.Actor;
import cn.zhixiang.platform.shared.*;
import tools.jackson.databind.json.JsonMapper;
/** 消息保存与历史查询；当前文本接口不伪造检索引用，RAG快照接入后统一持久化引用。 */
@Service
public class MessageService {
 private final MessageMapper mapper;private final RunJournalMapper runs;private final ConversationService conversations;private final JsonMapper json;
 /** 创建用例。
 * @param mapper 消息端口
 * @param runs 运行锁
 * @param conversations 会话授权
 * @param json 引用解码 */
 public MessageService(MessageMapper mapper,RunJournalMapper runs,ConversationService conversations,JsonMapper json){this.mapper=mapper;this.runs=runs;this.conversations=conversations;this.json=json;}
 /** 内部运行用例保存不可变文本，同运行同角色重复相同内容返回原ID。
 * @param run 已由调用方授权的运行
 * @param role USER/ASSISTANT
 * @param text 完整正文，不允许静默截断
 * @return 持久消息ID
 * @throws BusinessException 参数、状态错误或重复消息内容冲突 */
 @Transactional(rollbackFor=Exception.class)
 public UUID save(UUID run,String role,String text){
  if(!Set.of("USER","ASSISTANT").contains(role)||text==null||text.isBlank()||text.codePointCount(0,text.length())>(role.equals("USER")?16000:262144))throw new BusinessException(422,"MESSAGE_INVALID","消息正文或角色不合法");
  var r=runs.lock(run);if(r==null)throw new BusinessException(404,"RESOURCE_NOT_FOUND","运行不存在");var old=mapper.existing(run,role);
  if(old!=null){if(!old.text().equals(text))throw new BusinessException(409,"MESSAGE_IMMUTABLE","已有消息不能覆盖");return old.id();}
  if(!(role.equals("USER")&&r.state().equals("RESERVED")||role.equals("ASSISTANT")&&r.state().equals("RUNNING")))throw new BusinessException(409,"RUN_STATE_INVALID","当前运行状态不能保存该角色消息");
  UUID id=UUID.randomUUID();mapper.insert(id,run,role,text);return id;
 }
 /** 本人消息历史，先检查当前项目归属和会话隐藏状态。
 * @param conversation 会话
 * @param q 分页/正文检索
 * @param actor 当前学生
 * @return 历史分页 */
 @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
 public PageResult<View> history(UUID conversation,PageQuery q,Actor actor){if(q.status()!=null)throw new BusinessException(400,"INVALID_FILTER","消息没有启停状态");conversations.get(conversation,actor);return new PageResult<>(mapper.list(conversation,q).stream().map(r->new View(r.id(),r.conversationId(),r.runId(),r.role(),r.text(),json.readTree(r.citations()),r.createdAt())).toList(),q.page(),q.size(),mapper.count(conversation,q));}
 /** 历史响应，不暴露运行上下文或供应商密钥。
 * @param id 消息ID
 * @param conversationId 会话
 * @param runId 运行
 * @param role 角色
 * @param text 完整正文
 * @param citations 引用数组
 * @param createdAt 创建时间 */
 public record View(UUID id,UUID conversationId,UUID runId,String role,String text,tools.jackson.databind.JsonNode citations,OffsetDateTime createdAt){}
}
