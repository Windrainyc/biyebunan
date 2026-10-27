package cn.zhixiang.platform.model.mapper;

import cn.zhixiang.platform.model.dto.KeyDtos;
import cn.zhixiang.platform.model.dto.ModelDtos;
import cn.zhixiang.platform.model.entity.KeyEntity;
import cn.zhixiang.platform.shared.dto.PageQuery;

import org.apache.ibatis.annotations.*;

import java.util.*;

/** 模型凭证数据访问；动态排序仅来自 PageQuery 封闭枚举映射。 */
@Mapper
public interface KeyMapper {
    /**
     * 返回当前页持久化投影。
     *
     * @param q 已校验分页条件
     * @return 当前页持久化投影
     */
    @Select(
            "<script>SELECT"
                + " id,version,created_at,updated_at,name,model_id,masked_suffix,status,rotated_at,secret_ref"
                + " FROM model_key WHERE name ILIKE #{q.pattern} <if test='q.status != null'>AND"
                + " status=#{q.status}</if> ORDER BY ${q.orderBy} LIMIT #{q.size} OFFSET"
                + " #{q.offset}</script>")
    List<KeyEntity> list(@Param("q") PageQuery q);

    /**
     * 返回过滤后总数。
     *
     * @param q 与列表一致的过滤条件
     * @return 过滤后总数
     */
    @Select(
            "<script>SELECT count(*) FROM model_key WHERE name ILIKE #{q.pattern} <if"
                    + " test='q.status != null'>AND status=#{q.status}</if></script>")
    long count(@Param("q") PageQuery q);

    /**
     * 返回资源或 null。
     *
     * @param id 标识
     * @return 资源或 null
     */
    @Select(
            "SELECT"
                + " id,version,created_at,updated_at,name,model_id,masked_suffix,status,rotated_at,secret_ref"
                + " FROM model_key WHERE id=#{id}")
    KeyEntity get(UUID id);

    /**
     * 返回加锁资源；串行化引用创建、状态更改和删除。
     *
     * @param id 标识
     * @return 加锁资源；串行化引用创建、状态更改和删除
     */
    @Select(
            "SELECT"
                + " id,version,created_at,updated_at,name,model_id,masked_suffix,status,rotated_at,secret_ref"
                + " FROM model_key WHERE id=#{id} FOR UPDATE")
    KeyEntity lock(UUID id);

    /**
     * 在调用方业务事务内插入记录，由数据库约束保证唯一性和引用完整性。
     *
     * @param id 新标识
     * @param w 已验证写入值
     * @param ref 加密秘密引用
     * @param suffix 脱敏尾部
     * @param actor 服务端操作者
     */
    @Insert(
            "INSERT INTO"
                + " model_key(id,name,model_id,masked_suffix,status,secret_ref,created_by,updated_by)"
                + " VALUES(#{id},#{w.name},#{w.modelId},#{suffix},'DISABLED',#{ref},#{actor},#{actor})")
    void insert(UUID id, @Param("w") KeyDtos.Write w, UUID actor, UUID ref, String suffix);

    /**
     * 更换密文引用，元数据及版本在同一事务更新。
     *
     * @param id 标识
     * @param version 期望版本
     * @param ref 新秘密引用
     * @param suffix 脱敏尾部
     * @param actor 操作者
     * @return CAS 命中数
     */
    @Update(
            "UPDATE model_key SET"
                + " secret_ref=#{ref},masked_suffix=#{suffix},rotated_at=clock_timestamp(),version=version+1,updated_at=now(),updated_by=#{actor}"
                + " WHERE id=#{id} AND version=#{version}")
    int rotate(UUID id, long version, UUID ref, String suffix, UUID actor);

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
            "UPDATE model_key SET"
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
    @Delete("DELETE FROM model_key WHERE id=#{id} AND version=#{version}")
    int delete(UUID id, long version);

    /**
     * 删除已解除引用的旧密文；与轮换/删除凭证同事务。
     *
     * @param ref 旧引用
     */
    @Delete("DELETE FROM secret_material WHERE id=#{ref}")
    void erase(UUID ref);
}
