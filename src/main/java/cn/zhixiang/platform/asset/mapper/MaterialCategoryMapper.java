package cn.zhixiang.platform.asset.mapper;

import cn.zhixiang.platform.asset.dto.MaterialCategoryDtos;
import cn.zhixiang.platform.asset.entity.MaterialCategoryEntity;
import cn.zhixiang.platform.shared.dto.PageQuery;

import org.apache.ibatis.annotations.*;

import java.util.*;

/** 素材分类数据访问；动态排序仅来自 PageQuery 封闭枚举映射。 */
@Mapper
public interface MaterialCategoryMapper {
    /**
     * 返回当前页持久化投影。
     *
     * @param q 已校验分页条件
     * @return 当前页持久化投影
     */
    @Select(
            "<script>SELECT id,version,created_at,updated_at,name,code,status FROM"
                + " material_category WHERE (name ILIKE #{q.pattern} ESCAPE E'\\\\' OR code ILIKE"
                + " #{q.pattern} ESCAPE E'\\\\') <if test='q.status != null'>AND"
                + " status=#{q.status}</if> ORDER BY ${q.orderBy} LIMIT #{q.size} OFFSET"
                + " #{q.offset}</script>")
    List<MaterialCategoryEntity> list(@Param("q") PageQuery q);

    /**
     * 返回过滤后总数。
     *
     * @param q 与列表一致的过滤条件
     * @return 过滤后总数
     */
    @Select(
            "<script>SELECT count(*) FROM material_category WHERE (name ILIKE #{q.pattern} ESCAPE"
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
            "SELECT id,version,created_at,updated_at,name,code,status FROM material_category WHERE"
                    + " id=#{id}")
    MaterialCategoryEntity get(UUID id);

    /**
     * 返回加锁资源；串行化引用创建、状态更改和删除。
     *
     * @param id 标识
     * @return 加锁资源；串行化引用创建、状态更改和删除
     */
    @Select(
            "SELECT id,version,created_at,updated_at,name,code,status FROM material_category WHERE"
                    + " id=#{id} FOR UPDATE")
    MaterialCategoryEntity lock(UUID id);

    /**
     * 在调用方业务事务内插入记录，由数据库约束保证唯一性和引用完整性。
     *
     * @param id 新标识
     * @param w 已验证写入值
     * @param actor 服务端操作者
     */
    @Insert(
            "INSERT INTO material_category(id,name,code,status,created_by,updated_by)"
                    + " VALUES(#{id},#{w.name},#{w.code},#{w.status},#{actor},#{actor})")
    void insert(UUID id, @Param("w") MaterialCategoryDtos.Write w, UUID actor);

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
            "UPDATE material_category SET"
                + " name=#{w.name},code=#{w.code},status=#{w.status},version=version+1,updated_at=now(),updated_by=#{actor}"
                + " WHERE id=#{id} AND version=#{version}")
    int update(UUID id, long version, @Param("w") MaterialCategoryDtos.Write w, UUID actor);

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
            "UPDATE material_category SET"
                + " status=#{status},version=version+1,updated_at=now(),updated_by=#{actor} WHERE"
                + " id=#{id} AND version=#{version}")
    int status(UUID id, long version, MaterialCategoryDtos.Status status, UUID actor);

    /**
     * 返回删除数量；引用由数据库外键保护。
     *
     * @param id 标识
     * @param version 期望版本
     * @return 删除数量；引用由数据库外键保护
     */
    @Delete("DELETE FROM material_category WHERE id=#{id} AND version=#{version}")
    int delete(UUID id, long version);
}
