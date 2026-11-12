package cn.zhixiang.platform.model.mapper;

import cn.zhixiang.platform.model.dto.ModelDtos;
import cn.zhixiang.platform.model.entity.ModelEntity;
import cn.zhixiang.platform.shared.dto.PageQuery;

import org.apache.ibatis.annotations.*;

import java.util.*;

/** 模型数据访问；动态排序仅来自 PageQuery 封闭枚举映射。 */
@Mapper
public interface ModelMapper {
    /**
     * 服务端统一选择启用且有价格和凭证的模型；学生请求不能覆盖选择。
     *
     * @return 按创建时间和标识稳定排序的默认模型，无配置返回空
     */
    @Select(
            "SELECT d.id FROM model_definition d WHERE d.status='ENABLED' AND"
                + " d.provider='OPENAI_COMPATIBLE' AND EXISTS(SELECT 1 FROM model_price p WHERE"
                + " p.model_id=d.id AND p.status='ENABLED' AND p.effective_from<=clock_timestamp())"
                + " AND EXISTS(SELECT 1 FROM model_key k WHERE k.model_id=d.id AND"
                + " k.status='ENABLED') ORDER BY d.created_at,d.id LIMIT 1")
    UUID defaultModel();

    /**
     * 返回当前页持久化投影。
     *
     * @param q 已校验分页条件
     * @return 当前页持久化投影
     */
    @Select(
            "<script>SELECT"
                + " id,version,created_at,updated_at,name,code,provider,endpoint,context_limit,max_output_tokens,status"
                + " FROM model_definition WHERE (name ILIKE #{q.pattern} ESCAPE E'\\\\' OR code"
                + " ILIKE #{q.pattern} ESCAPE E'\\\\') <if test='q.status != null'>AND"
                + " status=#{q.status}</if> ORDER BY ${q.orderBy} LIMIT #{q.size} OFFSET"
                + " #{q.offset}</script>")
    List<ModelEntity> list(@Param("q") PageQuery q);

    /**
     * 返回过滤后总数。
     *
     * @param q 与列表一致的过滤条件
     * @return 过滤后总数
     */
    @Select(
            "<script>SELECT count(*) FROM model_definition WHERE (name ILIKE #{q.pattern} ESCAPE"
                    + " E'\\\\' OR code ILIKE #{q.pattern} ESCAPE E'\\\\') <if test='q.status !="
                    + " null'>AND status=#{q.status}</if></script>")
    long count(@Param("q") PageQuery q);

    /**
     * 返回资源或 null。
     *
     * @param id 标识
     * @return 资源或 null
     */
    @Select(
            "SELECT"
                + " id,version,created_at,updated_at,name,code,provider,endpoint,context_limit,max_output_tokens,status"
                + " FROM model_definition WHERE id=#{id}")
    ModelEntity get(UUID id);

    /**
     * 返回加锁资源；串行化引用创建、状态更改和删除。
     *
     * @param id 标识
     * @return 加锁资源；串行化引用创建、状态更改和删除
     */
    @Select(
            "SELECT"
                + " id,version,created_at,updated_at,name,code,provider,endpoint,context_limit,max_output_tokens,status"
                + " FROM model_definition WHERE id=#{id} FOR UPDATE")
    ModelEntity lock(UUID id);

    /**
     * 在调用方业务事务内插入记录，由数据库约束保证唯一性和引用完整性。
     *
     * @param id 新标识
     * @param w 已验证写入值
     * @param actor 服务端操作者
     */
    @Insert(
            "INSERT INTO"
                + " model_definition(id,name,code,provider,endpoint,context_limit,max_output_tokens,status,created_by,updated_by)"
                + " VALUES(#{id},#{w.name},#{w.code},#{w.provider},#{w.endpoint},#{w.contextLimit},#{w.maxOutputTokens},#{w.status},#{actor},#{actor})")
    void insert(UUID id, @Param("w") ModelDtos.Write w, UUID actor);

    /**
     * 返回CAS 命中数。
     *
     * @param id 标识
     * @param version 期望版本
     * @param w 新值
     * @param actor 服务端操作者
     * @return CAS 命中数
     */
    @Update(
            "UPDATE model_definition SET"
                + " name=#{w.name},code=#{w.code},provider=#{w.provider},endpoint=#{w.endpoint},context_limit=#{w.contextLimit},max_output_tokens=#{w.maxOutputTokens},status=#{w.status},version=version+1,updated_at=now(),updated_by=#{actor}"
                + " WHERE id=#{id} AND version=#{version}")
    int update(UUID id, long version, @Param("w") ModelDtos.Write w, UUID actor);

    /**
     * 返回CAS 命中数。
     *
     * @param id 标识
     * @param version 期望版本
     * @param status 目标状态
     * @param actor 服务端操作者
     * @return CAS 命中数
     */
    @Update(
            "UPDATE model_definition SET"
                + " status=#{status},version=version+1,updated_at=now(),updated_by=#{actor} WHERE"
                + " id=#{id} AND version=#{version}")
    int status(UUID id, long version, ModelDtos.Status status, UUID actor);

    /**
     * 返回删除数量；引用由数据库外键保护。
     *
     * @param id 标识
     * @param version 期望版本
     * @return 删除数量；引用由数据库外键保护
     */
    @Delete("DELETE FROM model_definition WHERE id=#{id} AND version=#{version}")
    int delete(UUID id, long version);

    /**
     * 检查模型是否已经被配置引用，调用前持模型行锁。
     *
     * @param id 模型标识
     * @return 存在价格或凭证
     */
    @Select(
            "SELECT EXISTS(SELECT 1 FROM model_price WHERE model_id=#{id}) OR EXISTS(SELECT 1 FROM"
                    + " model_key WHERE model_id=#{id})")
    boolean referenced(UUID id);
}
