package cn.zhixiang.platform.identity.mapper;

import cn.zhixiang.platform.identity.dto.RoleDtos;
import cn.zhixiang.platform.identity.entity.RoleEntity;
import cn.zhixiang.platform.shared.dto.PageQuery;

import org.apache.ibatis.annotations.*;

import java.util.*;

/** 角色与权限目录持久化访问，不进行客户端授权判断。 */
@Mapper
public interface RoleMapper {
    /**
     * 返回已锁定的身份配置互斥行。
     *
     * @return 已锁定的身份配置互斥行
     */
    @Select("SELECT id FROM iam_mutex WHERE id=1 FOR UPDATE")
    int lockIam();

    /**
     * 返回当前页。
     *
     * @param q 分页条件
     * @return 当前页
     */
    @Select(
            "<script>SELECT * FROM role WHERE (name ILIKE #{q.pattern} OR code ILIKE #{q.pattern})"
                + " <if test='q.status != null'>AND status=#{q.status}</if> ORDER BY ${q.orderBy}"
                + " LIMIT #{q.size} OFFSET #{q.offset}</script>")
    List<RoleEntity> list(@Param("q") PageQuery q);

    /**
     * 返回总数。
     *
     * @param q 相同过滤
     * @return 总数
     */
    @Select(
            "<script>SELECT count(*) FROM role WHERE (name ILIKE #{q.pattern} OR code ILIKE"
                + " #{q.pattern}) <if test='q.status != null'>AND status=#{q.status}</if></script>")
    long count(@Param("q") PageQuery q);

    /**
     * 返回角色或空。
     *
     * @param id 标识
     * @return 角色或空
     */
    @Select("SELECT * FROM role WHERE id=#{id}")
    RoleEntity get(UUID id);

    /**
     * 返回锁定角色。
     *
     * @param id 标识
     * @return 锁定角色
     */
    @Select("SELECT * FROM role WHERE id=#{id} FOR UPDATE")
    RoleEntity lock(UUID id);

    /**
     * 返回已分配权限。
     *
     * @param id 角色
     * @return 已分配权限
     */
    @Select(
            "SELECT permission_code FROM role_permission WHERE role_id=#{id} ORDER BY"
                    + " permission_code")
    List<String> codes(UUID id);

    /**
     * 返回可用于此平台的权限。
     *
     * @param platform 平台
     * @return 可用于此平台的权限
     */
    @Select("SELECT code FROM permission_platform WHERE platform=#{platform}")
    List<String> allowed(String platform);

    /**
     * 返回完整权限目录；服务层按操作者权限过滤。
     *
     * @return 完整权限目录；服务层按操作者权限过滤
     */
    @Select("SELECT code,module,description FROM permission ORDER BY code")
    List<RoleDtos.Permission> permissions();

    /**
     * 在调用方业务事务内插入记录，由数据库约束保证唯一性和引用完整性。
     *
     * @param id 标识
     * @param w 新角色
     */
    @Insert(
            "INSERT INTO role(id,name,code,platform,data_scope,status)"
                    + " VALUES(#{id},#{w.name},#{w.code},#{w.platform},#{w.dataScope},#{w.status})")
    void insert(UUID id, @Param("w") RoleDtos.Write w);

    /**
     * 返回修改数。
     *
     * @param id 标识
     * @param version 期望版本
     * @param w 新值
     * @return 修改数
     */
    @Update(
            "UPDATE role SET"
                + " name=#{w.name},code=#{w.code},data_scope=#{w.dataScope},status=#{w.status},version=version+1,updated_at=now()"
                + " WHERE id=#{id} AND version=#{version}")
    int update(UUID id, long version, @Param("w") RoleDtos.Write w);

    /**
     * 在调用方业务事务内插入记录，由数据库约束保证唯一性和引用完整性。
     *
     * @param id 角色
     * @param code 权限
     */
    @Insert("INSERT INTO role_permission VALUES(#{id},#{code})")
    void addPermission(UUID id, String code);

    /**
     * 在调用方业务事务内移除指定关联，约束冲突由上层统一转换。
     *
     * @param id 角色；替换前清空旧关联
     */
    @Delete("DELETE FROM role_permission WHERE role_id=#{id}")
    void clearPermissions(UUID id);

    /**
     * 在调用方业务事务内执行条件更新，不单独提交事务。
     *
     * @param id 角色；提升所有关联账户鉴权版本
     */
    @Update(
            "UPDATE user_account SET auth_version=auth_version+1 WHERE id IN (SELECT user_id FROM"
                    + " user_role WHERE role_id=#{id})")
    void invalidate(UUID id);

    /**
     * 返回删除数量；外键阻止删除仍在使用的角色。
     *
     * @param id 角色
     * @param version 期望版本
     * @return 删除数量；外键阻止删除仍在使用的角色
     */
    @Delete("DELETE FROM role WHERE id=#{id} AND version=#{version}")
    int delete(UUID id, long version);
}
