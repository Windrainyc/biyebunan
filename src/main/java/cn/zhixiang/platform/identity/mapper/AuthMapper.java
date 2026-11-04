package cn.zhixiang.platform.identity.mapper;

import cn.zhixiang.platform.identity.entity.AuthAccountEntity;
import cn.zhixiang.platform.identity.entity.AuthSessionRowEntity;

import org.apache.ibatis.annotations.*;

import java.util.*;

/** 认证持久化访问；锁顺序固定为账户后会话，避免刷新与停用并发死锁。 */
@Mapper
public interface AuthMapper {
    /**
     * 返回账户，可空。
     *
     * @param platform 路径确定的平台
     * @param phone 手机号摘要
     * @return 账户，可空
     */
    @Select(
            "SELECT"
                + " id,version,platform,display_name,phone_masked,password_hash,status,auth_version,school_id,major_id,mfa_secret_ref,created_at,updated_at"
                + " FROM user_account WHERE platform=#{platform} AND phone_digest=#{phone}")
    AuthAccountEntity find(String platform, String phone);

    /**
     * 返回加锁账户。
     *
     * @param id 账户标识
     * @return 加锁账户
     */
    @Select(
            "SELECT"
                + " id,version,platform,display_name,phone_masked,password_hash,status,auth_version,school_id,major_id,mfa_secret_ref,created_at,updated_at"
                + " FROM user_account WHERE id=#{id} FOR UPDATE")
    AuthAccountEntity lockAccount(UUID id);

    /**
     * 返回当前角色列表。
     *
     * @param id 账户标识
     * @return 当前角色列表
     */
    @Select("SELECT role_id FROM user_role WHERE user_id=#{id}")
    List<UUID> roles(UUID id);

    /**
     * 在调用方业务事务内插入记录，由数据库约束保证唯一性和引用完整性。
     *
     * @param r 新会话，全部令牌为摘要
     */
    @Insert(
            "INSERT INTO"
                + " auth_session(id,user_id,access_digest,refresh_digest,csrf_digest,auth_version,expires_at,refresh_expires_at,family_id)"
                + " VALUES(#{id},#{userId},#{accessDigest},#{refreshDigest},#{csrfDigest},#{authVersion},#{expiresAt},#{refreshExpiresAt},#{familyId})")
    void insert(AuthSessionRowEntity r);

    /**
     * 返回会话，无锁定位账户后再依次加锁。
     *
     * @param digest 刷新摘要
     * @return 会话，无锁定位账户后再依次加锁
     */
    @Select(
            "SELECT"
                + " id,user_id,access_digest,refresh_digest,csrf_digest,auth_version,expires_at,refresh_expires_at,family_id,consumed_at,revoked_at"
                + " FROM auth_session WHERE refresh_digest=#{digest}")
    AuthSessionRowEntity findRefresh(String digest);

    /**
     * 返回锁定最新会话状态。
     *
     * @param id 会话标识
     * @return 锁定最新会话状态
     */
    @Select(
            "SELECT"
                + " id,user_id,access_digest,refresh_digest,csrf_digest,auth_version,expires_at,refresh_expires_at,family_id,consumed_at,revoked_at"
                + " FROM auth_session WHERE id=#{id} FOR UPDATE")
    AuthSessionRowEntity lockSession(UUID id);

    /**
     * 在调用方业务事务内执行条件更新，不单独提交事务。
     *
     * @param id 旧会话标识；消费同时撤销旧 access
     */
    @Update("UPDATE auth_session SET consumed_at=now(),revoked_at=now() WHERE id=#{id}")
    void consume(UUID id);

    /**
     * 在调用方业务事务内执行条件更新，不单独提交事务。
     *
     * @param family 令牌族；重放或登出撤销族内所有会话
     */
    @Update(
            "UPDATE auth_session SET revoked_at=COALESCE(revoked_at,now()) WHERE"
                    + " family_id=#{family}")
    void revoke(UUID family);

    /**
     * 返回1 表示首次消费，0 表示重放。
     *
     * @param id 账户
     * @param step 已验证时间步
     * @return 1 表示首次消费，0 表示重放
     */
    @Update(
            "UPDATE user_account SET mfa_last_step=#{step} WHERE id=#{id} AND"
                    + " mfa_last_step<#{step}")
    int consumeMfa(UUID id, long step);

    /**
     * 返回当前十分钟窗口内请求次数。
     *
     * @param bucket 端和手机号或IP摘要
     * @return 当前十分钟窗口内请求次数
     */
    @Select(
            """
INSERT INTO auth_attempt_window(bucket,window_start,attempts) VALUES(#{bucket},now(),1)
ON CONFLICT(bucket) DO UPDATE SET
attempts=CASE WHEN auth_attempt_window.window_start<now()-interval '10 minutes' THEN 1 ELSE auth_attempt_window.attempts+1 END,
window_start=CASE WHEN auth_attempt_window.window_start<now()-interval '10 minutes' THEN now() ELSE auth_attempt_window.window_start END
RETURNING attempts
""")
    int attempt(String bucket);

    /**
     * 成功登录事务内保存记录，不将刷新计作新登录。
     *
     * @param id 事件
     * @param user 用户
     * @param platform 平台
     * @param ip 观测来源
     */
    @Insert(
            "INSERT INTO user_activity(id,actor_id,platform,kind,path,ip,time_bucket)"
                + " VALUES(#{id},#{user},#{platform},'LOGIN',concat('/',#{platform}::text,'/login'),#{ip},floor(extract(epoch"
                + " from now())/30))")
    void loginActivity(UUID id, UUID user, String platform, String ip);
}
