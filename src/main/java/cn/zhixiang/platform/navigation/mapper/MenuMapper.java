package cn.zhixiang.platform.navigation.mapper;

import cn.zhixiang.platform.navigation.dto.MenuDtos;
import cn.zhixiang.platform.navigation.entity.MenuEntity;

import org.apache.ibatis.annotations.*;

import java.util.*;

/** 菜单持久化，SQL权限过滤在分页之前完成，避免总数或分页泄漏未授权项。 */
@Mapper
public interface MenuMapper {
    /** 当前账户同平台的启用角色权限子查询。 */
    String GRANTS =
            "SELECT rp.permission_code FROM user_role ur JOIN role r ON r.id=ur.role_id JOIN"
                    + " role_permission rp ON rp.role_id=r.id WHERE ur.user_id=#{actor} AND"
                    + " r.platform=#{platform} AND r.status='ENABLED'";

    /** 从授权启用根节点递归构建完整可见祖先链。 */
    String VISIBLE =
            "WITH RECURSIVE visible AS (SELECT m.* FROM platform_menu m WHERE"
                    + " m.platform=#{platform} AND m.parent_id IS NULL AND m.status='ENABLED' AND"
                    + " m.permission_code IN ("
                    + GRANTS
                    + ") UNION ALL SELECT m.* FROM platform_menu m JOIN visible p ON"
                    + " m.parent_id=p.id WHERE m.platform=#{platform} AND m.status='ENABLED' AND"
                    + " m.permission_code IN ("
                    + GRANTS
                    + ")) ";

    /**
     * 返回锁定树互斥行；仅用于管理端低频结构变更。
     *
     * @return 锁定树互斥行；仅用于管理端低频结构变更
     */
    @Select("SELECT id FROM menu_tree_mutex WHERE id=1 FOR UPDATE")
    int lockTree();

    /**
     * 返回元数据页。
     *
     * @param q 分页
     * @param platform 平台过滤，可空
     * @return 元数据页
     */
    @Select(
            "<script>SELECT * FROM platform_menu WHERE (name ILIKE #{q.pattern} OR code ILIKE"
                    + " #{q.pattern}) <if test='q.status != null'>AND status=#{q.status}</if> <if"
                    + " test='platform != null'>AND platform=#{platform}</if> ORDER BY ${q.orderBy}"
                    + " LIMIT #{q.size} OFFSET #{q.offset}</script>")
    List<MenuEntity> list(@Param("q") MenuDtos.Query q, String platform);

    /**
     * 返回同范围总数。
     *
     * @param q 条件
     * @param platform 平台
     * @return 同范围总数
     */
    @Select(
            "<script>SELECT count(*) FROM platform_menu WHERE (name ILIKE #{q.pattern} OR code"
                + " ILIKE #{q.pattern}) <if test='q.status != null'>AND status=#{q.status}</if> <if"
                + " test='platform != null'>AND platform=#{platform}</if></script>")
    long count(@Param("q") MenuDtos.Query q, String platform);

    /**
     * 返回授权祖先链完整的可见菜单。
     *
     * @param q 分页
     * @param platform 当前平台
     * @param actor 当前身份
     * @return 授权祖先链完整的可见菜单
     */
    @Select(
            "<script>"
                    + VISIBLE
                    + "SELECT * FROM visible WHERE (name ILIKE #{q.pattern} OR code ILIKE"
                    + " #{q.pattern}) <if test='q.status != null'>AND status=#{q.status}</if> ORDER"
                    + " BY ${q.orderBy} LIMIT #{q.size} OFFSET #{q.offset}</script>")
    List<MenuEntity> visible(@Param("q") MenuDtos.Query q, String platform, UUID actor);

    /**
     * 返回已过滤总数。
     *
     * @param q 条件
     * @param platform 当前平台
     * @param actor 当前身份
     * @return 已过滤总数
     */
    @Select(
            "<script>"
                    + VISIBLE
                    + "SELECT count(*) FROM visible WHERE (name ILIKE #{q.pattern} OR code ILIKE"
                    + " #{q.pattern}) <if test='q.status != null'>AND"
                    + " status=#{q.status}</if></script>")
    long visibleCount(@Param("q") MenuDtos.Query q, String platform, UUID actor);

    /**
     * 返回元数据。
     *
     * @param id 菜单
     * @return 元数据
     */
    @Select("SELECT * FROM platform_menu WHERE id=#{id}")
    MenuEntity get(UUID id);

    /**
     * 返回启用子项数。
     *
     * @param parent 父菜单
     * @return 启用子项数
     */
    @Select("SELECT count(*) FROM platform_menu WHERE parent_id=#{parent} AND status='ENABLED'")
    long enabledChildren(UUID parent);

    /**
     * 在调用方业务事务内插入记录，由数据库约束保证唯一性和引用完整性。
     *
     * @param id 标识
     * @param w 新菜单
     * @param actor 操作者
     */
    @Insert(
            "INSERT INTO"
                + " platform_menu(id,name,code,platform,parent_id,path,permission_code,sort_order,status,created_by,updated_by)"
                + " VALUES(#{id},#{w.name},#{w.code},#{w.platform},#{w.parentId},#{w.path},#{w.permissionCode},#{w.sortOrder},#{w.status},#{actor},#{actor})")
    void insert(UUID id, @Param("w") MenuDtos.Write w, UUID actor);

    /**
     * 返回更新数。
     *
     * @param id 标识
     * @param version CAS
     * @param w 新值
     * @param actor 操作者
     * @return 更新数
     */
    @Update(
            "UPDATE platform_menu SET"
                + " name=#{w.name},code=#{w.code},parent_id=#{w.parentId},path=#{w.path},permission_code=#{w.permissionCode},sort_order=#{w.sortOrder},status=#{w.status},version=version+1,updated_at=now(),updated_by=#{actor}"
                + " WHERE id=#{id} AND version=#{version}")
    int update(UUID id, long version, @Param("w") MenuDtos.Write w, UUID actor);

    /**
     * 返回删除数；子项外键禁止删除。
     *
     * @param id 标识
     * @return 删除数；子项外键禁止删除
     */
    @Delete("DELETE FROM platform_menu WHERE id=#{id}")
    int delete(UUID id);
}
