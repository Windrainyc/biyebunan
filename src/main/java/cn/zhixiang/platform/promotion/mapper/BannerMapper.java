package cn.zhixiang.platform.promotion.mapper;

import cn.zhixiang.platform.promotion.dto.BannerDtos;
import cn.zhixiang.platform.promotion.entity.BannerEntity;
import cn.zhixiang.platform.shared.dto.PageQuery;

import org.apache.ibatis.annotations.*;

import java.util.*;

/** 首页横幅数据访问；动态排序仅来自 PageQuery 封闭枚举映射。 */
@Mapper
public interface BannerMapper {
    /**
     * 返回当前页持久化投影。
     *
     * @param q 已校验分页条件
     * @return 当前页持久化投影
     */
    @Select(
            "<script>SELECT"
                + " id,version,created_at,updated_at,title,subtitle,cover_asset_id,poster_asset_id,body,sort_order,status"
                + " FROM homepage_banner WHERE (title ILIKE #{q.pattern} ESCAPE E'\\\\' OR subtitle"
                + " ILIKE #{q.pattern} ESCAPE E'\\\\') <if test='q.status != null'>AND"
                + " status=#{q.status}</if> ORDER BY sort_order ASC,${q.orderBy} LIMIT #{q.size}"
                + " OFFSET #{q.offset}</script>")
    List<BannerEntity> list(@Param("q") PageQuery q);

    /**
     * 返回过滤后总数。
     *
     * @param q 与列表一致的过滤条件
     * @return 过滤后总数
     */
    @Select(
            "<script>SELECT count(*) FROM homepage_banner WHERE (title ILIKE #{q.pattern} ESCAPE"
                + " E'\\\\' OR subtitle ILIKE #{q.pattern} ESCAPE E'\\\\') <if test='q.status !="
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
                + " id,version,created_at,updated_at,title,subtitle,cover_asset_id,poster_asset_id,body,sort_order,status"
                + " FROM homepage_banner WHERE id=#{id}")
    BannerEntity get(UUID id);

    /**
     * 返回加锁资源；串行化引用创建、状态更改和删除。
     *
     * @param id 标识
     * @return 加锁资源；串行化引用创建、状态更改和删除
     */
    @Select(
            "SELECT"
                + " id,version,created_at,updated_at,title,subtitle,cover_asset_id,poster_asset_id,body,sort_order,status"
                + " FROM homepage_banner WHERE id=#{id} FOR UPDATE")
    BannerEntity lock(UUID id);

    /**
     * 在调用方业务事务内插入记录，由数据库约束保证唯一性和引用完整性。
     *
     * @param id 新标识
     * @param w 已验证写入值
     * @param actor 服务端操作者
     */
    @Insert(
            "INSERT INTO"
                + " homepage_banner(id,title,subtitle,cover_asset_id,poster_asset_id,body,sort_order,status,created_by,updated_by)"
                + " VALUES(#{id},#{w.title},#{w.subtitle},#{w.coverAssetId},#{w.posterAssetId},#{w.body},#{w.sortOrder},#{w.status},#{actor},#{actor})")
    void insert(UUID id, @Param("w") BannerDtos.Write w, UUID actor);

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
            "UPDATE homepage_banner SET"
                + " title=#{w.title},subtitle=#{w.subtitle},cover_asset_id=#{w.coverAssetId},poster_asset_id=#{w.posterAssetId},body=#{w.body},sort_order=#{w.sortOrder},status=#{w.status},version=version+1,updated_at=now(),updated_by=#{actor}"
                + " WHERE id=#{id} AND version=#{version}")
    int update(UUID id, long version, @Param("w") BannerDtos.Write w, UUID actor);

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
            "UPDATE homepage_banner SET"
                + " status=#{status},version=version+1,updated_at=now(),updated_by=#{actor} WHERE"
                + " id=#{id} AND version=#{version}")
    int status(UUID id, long version, BannerDtos.Status status, UUID actor);

    /**
     * 返回删除数量；引用由数据库外键保护。
     *
     * @param id 标识
     * @param version 期望版本
     * @return 删除数量；引用由数据库外键保护
     */
    @Delete("DELETE FROM homepage_banner WHERE id=#{id} AND version=#{version}")
    int delete(UUID id, long version);

    /**
     * 返回启用横幅，首页最多展示十条。
     *
     * @return 横幅
     */
    @Select(
            "SELECT"
                + " id,version,created_at,updated_at,title,subtitle,cover_asset_id,poster_asset_id,body,sort_order,status"
                + " FROM homepage_banner WHERE status='ENABLED' ORDER BY sort_order,id LIMIT 10")
    List<BannerEntity> active();
}
