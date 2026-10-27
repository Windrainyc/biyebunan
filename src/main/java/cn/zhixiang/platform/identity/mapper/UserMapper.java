package cn.zhixiang.platform.identity.mapper;

import cn.zhixiang.platform.identity.dto.UserDtos;
import cn.zhixiang.platform.identity.entity.AuthAccountEntity;
import cn.zhixiang.platform.shared.dto.PageQuery;

import org.apache.ibatis.annotations.*;

import java.util.*;

/** 用户管理数据端口；所有响应另映射为脱敏DTO。 */
@Mapper
public interface UserMapper {
    /**
     * 返回初始化审计标记数；重发只允许该来源且从未激活的账户。
     *
     * @param id 用户
     * @return 初始化审计标记数；重发只允许该来源且从未激活的账户
     */
    @Select(
            "SELECT count(*) FROM audit_event WHERE resource_type='user' AND resource_id=#{id} AND"
                    + " action='BOOTSTRAP_CREATED'")
    int bootstrapMarker(UUID id);

    /**
     * 返回所有管理员数量，包括待激活与停用；仅用于离线初始化保护。
     *
     * @return 所有管理员数量，包括待激活与停用；仅用于离线初始化保护
     */
    @Select("SELECT count(*) FROM user_account WHERE platform='admin'")
    long adminCount();

    /**
     * 返回当前页。
     *
     * @param q 查询
     * @param platform 平台过滤
     * @param roleId 角色过滤
     * @return 当前页
     */
    @Select(
            "<script>SELECT u.* FROM user_account u WHERE display_name ILIKE #{q.pattern} <if"
                + " test='q.status != null'>AND status=#{q.status}</if> <if test='platform !="
                + " null'>AND platform=#{platform}</if> <if test='roleId != null'>AND EXISTS(SELECT"
                + " 1 FROM user_role ur WHERE ur.user_id=u.id AND ur.role_id=#{roleId})</if> ORDER"
                + " BY ${q.orderBy} LIMIT #{q.size} OFFSET #{q.offset}</script>")
    List<AuthAccountEntity> list(@Param("q") PageQuery q, String platform, UUID roleId);

    /**
     * 返回同范围总数。
     *
     * @param q 查询
     * @param platform 平台
     * @param roleId 角色
     * @return 同范围总数
     */
    @Select(
            "<script>SELECT count(*) FROM user_account u WHERE display_name ILIKE #{q.pattern} <if"
                + " test='q.status != null'>AND status=#{q.status}</if> <if test='platform !="
                + " null'>AND platform=#{platform}</if> <if test='roleId != null'>AND EXISTS(SELECT"
                + " 1 FROM user_role ur WHERE ur.user_id=u.id AND"
                + " ur.role_id=#{roleId})</if></script>")
    long count(@Param("q") PageQuery q, String platform, UUID roleId);

    /**
     * 返回账户或null。
     *
     * @param id 账户标识
     * @return 账户或null
     */
    @Select("SELECT * FROM user_account WHERE id=#{id}")
    AuthAccountEntity get(UUID id);

    /**
     * 在调用方业务事务内插入记录，由数据库约束保证唯一性和引用完整性。
     *
     * @param id 账户
     * @param w 请求
     * @param phone 手机摘要
     * @param masked 脱敏号
     * @param password 不可登录的随机口令摘要，需激活覆盖
     */
    @Insert(
            "INSERT INTO"
                + " user_account(id,platform,display_name,phone_digest,phone_masked,password_hash,status)"
                + " VALUES(#{id},#{w.platform},#{w.displayName},#{phone},#{masked},#{password},'DISABLED')")
    void insert(
            UUID id, @Param("w") UserDtos.Create w, String phone, String masked, String password);

    /**
     * 返回更新数。
     *
     * @param id 账户
     * @param version 期望版本
     * @param w 新值
     * @return 更新数
     */
    @Update(
            "UPDATE user_account SET"
                + " display_name=#{w.displayName},school_id=#{w.schoolId},major_id=#{w.majorId},status=#{w.status},version=version+1,auth_version=auth_version+1,updated_at=now()"
                + " WHERE id=#{id} AND version=#{version}")
    int update(UUID id, long version, @Param("w") UserDtos.Write w);

    /**
     * 返回已激活数量。
     *
     * @param id 账户
     * @return 已激活数量
     */
    @Select("SELECT count(*) FROM user_account WHERE id=#{id} AND activated_at IS NOT NULL")
    int activated(UUID id);

    /**
     * 在调用方业务事务内移除指定关联，约束冲突由上层统一转换。
     *
     * @param id 账户，删除旧角色
     */
    @Delete("DELETE FROM user_role WHERE user_id=#{id}")
    void clearRoles(UUID id);

    /**
     * 在调用方业务事务内插入记录，由数据库约束保证唯一性和引用完整性。
     *
     * @param id 账户
     * @param role 角色
     */
    @Insert("INSERT INTO user_role VALUES(#{id},#{role})")
    void addRole(UUID id, UUID role);

    /**
     * 返回启用的超级管理员数量，必须持IAM全局锁。
     *
     * @return 启用的超级管理员数量，必须持IAM全局锁
     */
    @Select(
            "SELECT count(DISTINCT u.id) FROM user_account u JOIN user_role ur ON ur.user_id=u.id"
                + " JOIN role r ON r.id=ur.role_id WHERE u.status='ENABLED' AND u.platform='admin'"
                + " AND r.code='SUPER_ADMIN' AND r.built_in=true AND r.status='ENABLED'")
    long activeSupers();

    /**
     * 返回启用且匹配的关联数量。
     *
     * @param school 院校
     * @param major 专业
     * @return 启用且匹配的关联数量
     */
    @Select(
            "SELECT count(*) FROM school s LEFT JOIN major m ON m.school_id=s.id AND m.id=#{major}"
                + " WHERE s.id=#{school} AND s.status='ENABLED' AND (CAST(#{major} AS uuid) IS NULL"
                + " OR (m.id IS NOT NULL AND m.status='ENABLED'))")
    int validEducation(UUID school, UUID major);
}
