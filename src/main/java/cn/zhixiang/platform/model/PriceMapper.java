package cn.zhixiang.platform.model;
import org.apache.ibatis.annotations.*;
import java.util.*;
import cn.zhixiang.platform.shared.PageQuery;
/** 模型价格数据访问；动态排序仅来自 PageQuery 封闭枚举映射。 */
@Mapper
public interface PriceMapper {
 /** 返回当前页持久化投影。
 * @param q 已校验分页条件
 * @return 当前页持久化投影 */
 @Select("<script>SELECT id,version,created_at,updated_at,model_id,currency,input_per_million::text AS input_per_million,output_per_million::text AS output_per_million,cached_input_per_million::text AS cached_input_per_million,effective_from,status FROM model_price WHERE model_id IN (SELECT id FROM model_definition WHERE name ILIKE #{q.pattern} OR code ILIKE #{q.pattern}) <if test='q.status != null'>AND status=#{q.status}</if> ORDER BY ${q.orderBy} LIMIT #{q.size} OFFSET #{q.offset}</script>")
 List<Row> list(@Param("q") PageQuery q);
 /** 返回过滤后总数。
 * @param q 与列表一致的过滤条件
 * @return 过滤后总数 */
 @Select("<script>SELECT count(*) FROM model_price WHERE model_id IN (SELECT id FROM model_definition WHERE name ILIKE #{q.pattern} OR code ILIKE #{q.pattern}) <if test='q.status != null'>AND status=#{q.status}</if></script>")
 long count(@Param("q") PageQuery q);
 /** 返回资源或 null。
 * @param id 标识
 * @return 资源或 null */
 @Select("SELECT id,version,created_at,updated_at,model_id,currency,input_per_million::text AS input_per_million,output_per_million::text AS output_per_million,cached_input_per_million::text AS cached_input_per_million,effective_from,status FROM model_price WHERE id=#{id}")
 Row get(UUID id);
 /** 返回加锁资源；串行化引用创建、状态更改和删除。
 * @param id 标识
 * @return 加锁资源；串行化引用创建、状态更改和删除 */
 @Select("SELECT id,version,created_at,updated_at,model_id,currency,input_per_million::text AS input_per_million,output_per_million::text AS output_per_million,cached_input_per_million::text AS cached_input_per_million,effective_from,status FROM model_price WHERE id=#{id} FOR UPDATE")
 Row lock(UUID id);
 /** 在调用方业务事务内插入记录，由数据库约束保证唯一性和引用完整性。
 * @param id 新标识
 * @param w 已验证写入值
 * @param actor 服务端操作者 */
 @Insert("INSERT INTO model_price(id,model_id,currency,input_per_million,output_per_million,cached_input_per_million,effective_from,status,created_by,updated_by) VALUES(#{id},#{w.modelId},#{w.currency},CAST(#{w.inputPerMillion} AS numeric),CAST(#{w.outputPerMillion} AS numeric),CAST(#{w.cachedInputPerMillion} AS numeric),#{w.effectiveFrom},#{w.status},#{actor},#{actor})")
 void insert(UUID id,@Param("w") PriceDtos.Write w,UUID actor);
 /** 返回CAS 命中数。
 * @param id 标识
 * @param version 期望版本
 * @param w 新值
 * @param actor 服务端操作者
 * @return CAS 命中数 */
 @Update("UPDATE model_price SET currency=#{w.currency},input_per_million=CAST(#{w.inputPerMillion} AS numeric),output_per_million=CAST(#{w.outputPerMillion} AS numeric),cached_input_per_million=CAST(#{w.cachedInputPerMillion} AS numeric),effective_from=#{w.effectiveFrom},status=#{w.status},version=version+1,updated_at=now(),updated_by=#{actor} WHERE id=#{id} AND version=#{version}")
 int update(UUID id,long version,@Param("w") PriceDtos.Write w,UUID actor);
 /** 返回CAS 命中数。
 * @param id 标识
 * @param version 期望版本
 * @param status 目标状态
 * @param actor 服务端操作者
 * @return CAS 命中数 */
 @Update("UPDATE model_price SET status=#{status},version=version+1,updated_at=now(),updated_by=#{actor} WHERE id=#{id} AND version=#{version}")
 int status(UUID id,long version,ModelDtos.Status status,UUID actor);
 /** 返回删除数量；引用由数据库外键保护。
 * @param id 标识
 * @param version 期望版本
 * @return 删除数量；引用由数据库外键保护 */
 @Delete("DELETE FROM model_price WHERE id=#{id} AND version=#{version}")
 int delete(UUID id,long version);
 /** 持久化查询记录，仅供服务层映射和业务判断。
 * @param id 标识
  * @param version CAS
  * @param createdAt 创建
  * @param updatedAt 更新
   * @param modelId 模型标识，创建后不可更改
 * @param currency 币种，仅 CNY
 * @param inputPerMillion 每百万输入词元人民币价格，最多六位小数
 * @param outputPerMillion 每百万输出词元人民币价格
 * @param cachedInputPerMillion 每百万缓存命中词元人民币价格
 * @param effectiveFrom 生效时间，新价格必须在未来；生效后金额不可覆盖
 * @param status 是否可供新运行选择 */
 record Row(UUID id,long version,java.time.OffsetDateTime createdAt,java.time.OffsetDateTime updatedAt,UUID modelId,String currency,String inputPerMillion,String outputPerMillion,String cachedInputPerMillion,java.time.OffsetDateTime effectiveFrom,ModelDtos.Status status) {}

 /** 数据库时间用于已生效保护，避免节点时间漂移影响价格判断。
 * @return 数据库当前时间，锁等待后重新获取 */
 @Select("SELECT clock_timestamp()") java.time.OffsetDateTime now();
}
