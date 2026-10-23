package cn.zhixiang.platform.foundation;
import org.apache.ibatis.annotations.*;
import java.util.*;
import cn.zhixiang.platform.shared.PageQuery;
/** Major 数据访问；动态排序仅来自 PageQuery 封闭枚举映射。 */
@Mapper
public interface MajorMapper {
 /**
 * @param schoolId 可选院校过滤
 * @param q 已校验分页条件
 * @return 当前页持久化投影 */
 @Select("<script>SELECT id,version,created_at,updated_at,name,code,school_id,level,status FROM major WHERE (name ILIKE #{q.pattern} ESCAPE E'\\\\' OR code ILIKE #{q.pattern} ESCAPE E'\\\\') <if test='q.status != null'>AND status=#{q.status}</if> <if test='schoolId != null'>AND school_id=#{schoolId}</if> ORDER BY ${q.orderBy} LIMIT #{q.size} OFFSET #{q.offset}</script>")
 List<MajorDtos.View> list(@Param("q") PageQuery q,UUID schoolId);
 /**
 * @param schoolId 可选院校过滤
 * @param q 与列表一致的过滤条件
 * @return 过滤后总数 */
 @Select("<script>SELECT count(*) FROM major WHERE (name ILIKE #{q.pattern} ESCAPE E'\\\\' OR code ILIKE #{q.pattern} ESCAPE E'\\\\') <if test='q.status != null'>AND status=#{q.status}</if><if test='schoolId != null'>AND school_id=#{schoolId}</if></script>")
 long count(@Param("q") PageQuery q,UUID schoolId);
 /**
 * @param id 标识
 * @return 资源或 null */
 @Select("SELECT id,version,created_at,updated_at,name,code,school_id,level,status FROM major WHERE id=#{id}")
 MajorDtos.View get(UUID id);
 /**
 * @param id 标识
 * @return 加锁资源；串行化引用创建、状态更改和删除 */
 @Select("SELECT id,version,created_at,updated_at,name,code,school_id,level,status FROM major WHERE id=#{id} FOR UPDATE")
 MajorDtos.View lock(UUID id);
 /**
 * @param id 新标识
 * @param w 已验证写入值
 * @param actor 服务端操作者 */
 @Insert("INSERT INTO major(id,name,code,school_id,level,status,created_by,updated_by) VALUES(#{id},#{w.name},#{w.code},#{w.schoolId},#{w.level},#{w.status},#{actor},#{actor})")
 void insert(UUID id,@Param("w") MajorDtos.Write w,UUID actor);
 /**
 * @param id 标识
 * @param version 期望版本
 * @param w 新值
 * @param actor 服务端操作者
 * @return CAS 命中数 */
 @Update("UPDATE major SET name=#{w.name},code=#{w.code},school_id=#{w.schoolId},level=#{w.level},status=#{w.status},version=version+1,updated_at=now(),updated_by=#{actor} WHERE id=#{id} AND version=#{version}")
 int update(UUID id,long version,@Param("w") MajorDtos.Write w,UUID actor);
 /**
 * @param id 标识
 * @param version 期望版本
 * @param status 目标状态
 * @param actor 服务端操作者
 * @return CAS 命中数 */
 @Update("UPDATE major SET status=#{status},version=version+1,updated_at=now(),updated_by=#{actor} WHERE id=#{id} AND version=#{version}")
 int status(UUID id,long version,MajorDtos.Status status,UUID actor);
 /**
 * @param id 标识
 * @param version 期望版本
 * @return 删除数量；引用由数据库外键保护 */
 @Delete("DELETE FROM major WHERE id=#{id} AND version=#{version}")
 int delete(UUID id,long version);
}
