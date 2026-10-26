package cn.zhixiang.platform.notification;
import org.apache.ibatis.annotations.*;
import java.util.*;
import cn.zhixiang.platform.shared.PageQuery;
/** 通知模板数据访问；动态排序仅来自 PageQuery 封闭枚举映射。 */
@Mapper
public interface TemplateMapper {
 /** 返回当前页持久化投影。
 * @param q 已校验分页条件
 * @return 当前页持久化投影 */
 @Select("<script>SELECT id,version,created_at,updated_at,name,code,channel,provider_template_id,status FROM notification_template WHERE (name ILIKE #{q.pattern} ESCAPE E'\\\\' OR code ILIKE #{q.pattern} ESCAPE E'\\\\') <if test='q.status != null'>AND status=#{q.status}</if> ORDER BY ${q.orderBy} LIMIT #{q.size} OFFSET #{q.offset}</script>")
 List<Row> list(@Param("q") PageQuery q);
 /** 返回过滤后总数。
 * @param q 与列表一致的过滤条件
 * @return 过滤后总数 */
 @Select("<script>SELECT count(*) FROM notification_template WHERE (name ILIKE #{q.pattern} ESCAPE E'\\\\' OR code ILIKE #{q.pattern} ESCAPE E'\\\\') <if test='q.status != null'>AND status=#{q.status}</if></script>")
 long count(@Param("q") PageQuery q);
 /** 返回资源或 null。
 * @param id 标识
 * @return 资源或 null */
 @Select("SELECT id,version,created_at,updated_at,name,code,channel,provider_template_id,status FROM notification_template WHERE id=#{id}")
 Row get(UUID id);
 /** 返回加锁资源；串行化引用创建、状态更改和删除。
 * @param id 标识
 * @return 加锁资源；串行化引用创建、状态更改和删除 */
 @Select("SELECT id,version,created_at,updated_at,name,code,channel,provider_template_id,status FROM notification_template WHERE id=#{id} FOR UPDATE")
 Row lock(UUID id);
 /** 在调用方业务事务内插入记录，由数据库约束保证唯一性和引用完整性。
 * @param id 新标识
 * @param w 已验证写入值
 * @param actor 服务端操作者 */
 @Insert("INSERT INTO notification_template(id,name,code,channel,provider_template_id,status,created_by,updated_by) VALUES(#{id},#{w.name},#{w.code},#{w.channel},#{w.providerTemplateId},#{w.status},#{actor},#{actor})")
 void insert(UUID id,@Param("w") TemplateDtos.Write w,UUID actor);
 /** 返回CAS 命中数。
 * @param id 标识
 * @param version 期望版本
 * @param w 新值
 * @param actor 服务端操作者
 * @return CAS 命中数 */
 @Update("UPDATE notification_template SET name=#{w.name},code=#{w.code},channel=#{w.channel},provider_template_id=#{w.providerTemplateId},status=#{w.status},version=version+1,updated_at=now(),updated_by=#{actor} WHERE id=#{id} AND version=#{version}")
 int update(UUID id,long version,@Param("w") TemplateDtos.Write w,UUID actor);
 /** 返回CAS 命中数。
 * @param id 标识
 * @param version 期望版本
 * @param status 目标状态
 * @param actor 服务端操作者
 * @return CAS 命中数 */
 @Update("UPDATE notification_template SET status=#{status},version=version+1,updated_at=now(),updated_by=#{actor} WHERE id=#{id} AND version=#{version}")
 int status(UUID id,long version,TemplateDtos.Status status,UUID actor);
 /** 返回删除数量；引用由数据库外键保护。
 * @param id 标识
 * @param version 期望版本
 * @return 删除数量；引用由数据库外键保护 */
 @Delete("DELETE FROM notification_template WHERE id=#{id} AND version=#{version}")
 int delete(UUID id,long version);
 /** 持久化查询记录，仅供服务层映射和业务判断。
 * @param id 标识
  * @param version CAS
  * @param createdAt 创建
  * @param updatedAt 更新
  * @param name 名称
  * @param code 代码
  * @param channel 发送渠道
  * @param providerTemplateId 供应商模板编号或站内渲染器模板代码
  * @param status 状态 */
 record Row(UUID id,long version,java.time.OffsetDateTime createdAt,java.time.OffsetDateTime updatedAt,String name,String code,TemplateDtos.Channel channel,String providerTemplateId,TemplateDtos.Status status) {}

}
