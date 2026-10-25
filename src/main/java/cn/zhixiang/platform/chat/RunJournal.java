package cn.zhixiang.platform.chat;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import cn.zhixiang.platform.shared.BusinessException;
import tools.jackson.databind.json.JsonMapper;
/** 持久流事件服务；仅供已完成身份和数据范围校验的运行用例调用，不自行调用模型或账务。 */
@Service
public class RunJournal {
 private final RunJournalMapper mapper;private final JsonMapper json;
 /** 创建日志服务。
 * @param mapper 持久层
 * @param json 编解码 */
 public RunJournal(RunJournalMapper mapper,JsonMapper json){this.mapper=mapper;this.json=json;}
 /** 锁运行并原子追加；事务失败不会留下序号空洞。
 * @param run 运行
 * @param type accepted/delta/citation/usage/completed/failed/cancelled
 * @param payload 事件对象，不能包含供应商密钥
 * @return 新序号
 * @throws BusinessException 运行不存在、事件超限或已终结 */
 @Transactional(rollbackFor=Exception.class)
 public long append(UUID run,String type,Map<String,?> payload){
  if(!Set.of("accepted","delta","citation","usage","completed","failed","cancelled").contains(type))throw new BusinessException(422,"STREAM_EVENT_INVALID","未知事件类型");
  String body=json.writeValueAsString(payload);if(body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>65536)throw new BusinessException(422,"STREAM_EVENT_TOO_LARGE","流事件超过大小限制");
  var h=mapper.lock(run);if(h==null)throw new BusinessException(404,"RESOURCE_NOT_FOUND","运行不存在");
  if(Set.of("SUCCEEDED","FAILED","CANCELLED").contains(h.state())||mapper.terminal(run))throw new BusinessException(409,"RUN_TERMINAL","运行已结束，不能追加事件");
  long sequence=mapper.next(run);mapper.insert(run,sequence,type,body);return sequence;
 }
 /** 解析Last-Event-ID，不允许跨运行游标或负数/溢出。
 * @param run 当前运行
 * @param value 可空，格式runId:sequence
 * @return 已收到序号，首次为0
 * @throws BusinessException 游标不合法 */
 public long cursor(UUID run,String value){if(value==null)return 0;try{String[] parts=value.split(":",-1);if(parts.length!=2||!parts[0].equals(run.toString())||!parts[1].matches("[0-9]{1,19}"))throw new IllegalArgumentException();return Long.parseLong(parts[1]);}catch(IllegalArgumentException e){throw new BusinessException(400,"STREAM_CURSOR_INVALID","流游标不合法");}}
 /** 快照读取并检测保留窗口缺口；消费回放不修改运行或计费。
 * @param run 已授权运行
 * @param after 已收到序号
 * @param limit 每批1至100事件
 * @return 连续事件批次
 * @throws BusinessException 不存在、游标超前或回放过期 */
 @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
 public List<RunJournalMapper.Event> replay(UUID run,long after,int limit){
  if(after<0||limit<1||limit>100)throw new BusinessException(400,"STREAM_CURSOR_INVALID","流回放参数不合法");
  var head=mapper.head(run);if(head==null)throw new BusinessException(404,"RESOURCE_NOT_FOUND","运行不存在");if(after>head.lastSequence())throw new BusinessException(400,"STREAM_CURSOR_AHEAD","游标超过当前流序号");
  var events=mapper.after(run,after,limit);long expected=after;
  for(var event:events){if(event.sequence()!=++expected)throw expired();}
  if(events.size()<limit&&expected<head.lastSequence())throw expired();return events;
 }
 /** 已到终态且客户端已读完全部事件，可以立即关闭重连。
 * @param run 已授权运行
 * @param after 已收到序号
 * @return 是否无需继续等待 */
 public boolean complete(UUID run,long after){var head=mapper.head(run);return head!=null&&Set.of("SUCCEEDED","FAILED","CANCELLED").contains(head.state())&&after==head.lastSequence();}
 private BusinessException expired(){return new BusinessException(410,"STREAM_REPLAY_EXPIRED","流事件已过期，请读取消息历史");}
}
