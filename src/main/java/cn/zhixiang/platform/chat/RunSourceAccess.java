package cn.zhixiang.platform.chat;
import java.util.UUID;
import org.apache.ibatis.annotations.*;
import cn.zhixiang.platform.knowledge.*;
/** 来源当前权限与历史受众同时校验；来源撤回后历史生成文本不继续暴露。 */
@Mapper
public interface RunSourceAccess {
 /** 无权来源谓词，供消息分页保持总数与列表一致。 */
 String DENIED="EXISTS(SELECT 1 FROM run_source rs JOIN knowledge_version v ON v.id=rs.version_id JOIN knowledge_content c ON c.id=v.content_id WHERE rs.run_id=m.run_id AND NOT (c.deleted_at IS NULL AND c.state='PUBLISHED' AND "+ContentMapper.ACCESS+" AND "+KnowledgeQueryMapper.VACCESS+"))";
 /** 检查运行的全部来源仍可读取。
 * @param run 运行
 * @param student 学生
 * @return 是否可读取 */
 @Select("SELECT NOT EXISTS(SELECT 1 FROM (SELECT id AS run_id FROM generation_run WHERE id=#{run}) m WHERE "+DENIED+")") boolean allowed(UUID run,UUID student);
}
