package cn.zhixiang.platform.configuration;
import org.apache.ibatis.annotations.*;
import java.util.*;
import cn.zhixiang.platform.shared.PageQuery;
/** 配置数据访问；动态排序仅来自 PageQuery 封闭枚举映射。 */
@Mapper
public interface StorageMapper {
 /** 返回当前页持久化投影。
 * @param q 已校验分页条件
 * @return 当前页持久化投影 */
 @Select("<script>SELECT id,version,created_at,updated_at,name,provider,bucket,region,endpoint,credential_ref,access,status FROM storage_config WHERE (name ILIKE #{q.pattern} ESCAPE E'\\\\') <if test='q.status != null'>AND status=#{q.status}</if> ORDER BY ${q.orderBy} LIMIT #{q.size} OFFSET #{q.offset}</script>")
 List<Row> list(@Param("q") PageQuery q);
 /** 返回过滤后总数。
 * @param q 与列表一致的过滤条件
 * @return 过滤后总数 */
 @Select("<script>SELECT count(*) FROM storage_config WHERE (name ILIKE #{q.pattern} ESCAPE E'\\\\') <if test='q.status != null'>AND status=#{q.status}</if></script>")
 long count(@Param("q") PageQuery q);
 /** 返回资源或 null。
 * @param id 标识
 * @return 资源或 null */
 @Select("SELECT id,version,created_at,updated_at,name,provider,bucket,region,endpoint,credential_ref,access,status FROM storage_config WHERE id=#{id}")
 Row get(UUID id);
 /** 返回加锁资源；串行化引用创建、状态更改和删除。
 * @param id 标识
 * @return 加锁资源；串行化引用创建、状态更改和删除 */
 @Select("SELECT id,version,created_at,updated_at,name,provider,bucket,region,endpoint,credential_ref,access,status FROM storage_config WHERE id=#{id} FOR UPDATE")
 Row lock(UUID id);
 /** 在调用方业务事务内插入记录，由数据库约束保证唯一性和引用完整性。
 * @param id 新标识
 * @param w 已验证写入值
 * @param actor 服务端操作者 */
 @Insert("INSERT INTO storage_config(id,name,provider,bucket,region,endpoint,credential_ref,access,status,created_by,updated_by) VALUES(#{id},#{w.name},#{w.provider},#{w.bucket},#{w.region},#{w.endpoint},#{w.credentialRef},#{w.access},#{w.status},#{actor},#{actor})")
 void insert(UUID id,@Param("w") StorageDtos.Write w,UUID actor);
 /** 返回CAS 命中数。
 * @param id 标识
 * @param version 期望版本
 * @param w 新值
 * @param actor 服务端操作者
 * @return CAS 命中数 */
 @Update("UPDATE storage_config SET name=#{w.name},provider=#{w.provider},bucket=#{w.bucket},region=#{w.region},endpoint=#{w.endpoint},credential_ref=#{w.credentialRef},access=#{w.access},status=#{w.status},version=version+1,updated_at=now(),updated_by=#{actor} WHERE id=#{id} AND version=#{version}")
 int update(UUID id,long version,@Param("w") StorageDtos.Write w,UUID actor);
 /** 返回CAS 命中数。
 * @param id 标识
 * @param version 期望版本
 * @param status 目标状态
 * @param actor 服务端操作者
 * @return CAS 命中数 */
 @Update("UPDATE storage_config SET status=#{status},version=version+1,updated_at=now(),updated_by=#{actor} WHERE id=#{id} AND version=#{version}")
 int status(UUID id,long version,StorageDtos.Status status,UUID actor);
 /** 返回删除数量；引用由数据库外键保护。
 * @param id 标识
 * @param version 期望版本
 * @return 删除数量；引用由数据库外键保护 */
 @Delete("DELETE FROM storage_config WHERE id=#{id} AND version=#{version}")
 int delete(UUID id,long version);
 /** 持久化查询记录，仅供服务层映射和业务判断。
 * @param id 标识
  * @param version CAS
  * @param createdAt 创建
  * @param updatedAt 更新
   * @param name 配置展示名称
 * @param provider 对象存储供应商
 * @param bucket 私有桶名称
 * @param region 区域代码
 * @param endpoint 对象存储服务根地址
 * @param credentialRef 受保护运行配置中的凭证别名，不接收明文密钥或任意文件路径
 * @param access 访问级别，仅私有
 * @param status 是否允许业务选择此配置 */
 record Row(UUID id,long version,java.time.OffsetDateTime createdAt,java.time.OffsetDateTime updatedAt,String name,StorageDtos.Provider provider,String bucket,String region,String endpoint,String credentialRef,StorageDtos.Access access,StorageDtos.Status status) {}

}
