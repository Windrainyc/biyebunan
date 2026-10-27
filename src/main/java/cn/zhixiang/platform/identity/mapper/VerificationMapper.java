package cn.zhixiang.platform.identity.mapper;

import cn.zhixiang.platform.identity.entity.VerificationEntity;

import org.apache.ibatis.annotations.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 挑战持久化，校验与用途消费由应用服务短事务协调。 */
@Mapper
public interface VerificationMapper {
    /**
     * 在调用方业务事务内插入记录，由数据库约束保证唯一性和引用完整性。
     *
     * @param id 挑战
     * @param phone 手机摘要
     * @param purpose 用途
     * @param platform 平台
     * @param code 验证码摘要
     * @param expiry 过期时间
     */
    @Insert(
            "INSERT INTO"
                + " verification_challenge(id,phone_digest,purpose,platform,code_digest,state,expires_at)"
                + " VALUES(#{id},#{phone},#{purpose},#{platform},#{code},'PENDING',#{expiry})")
    void insert(
            UUID id,
            String phone,
            String purpose,
            String platform,
            String code,
            OffsetDateTime expiry);

    /**
     * 在调用方业务事务内执行条件更新，不单独提交事务。
     *
     * @param id 挑战
     * @param state READY或FAILED
     */
    @Update("UPDATE verification_challenge SET state=#{state} WHERE id=#{id} AND state='PENDING'")
    void sent(UUID id, String state);

    /**
     * 返回行，不锁定，用于确定账户锁顺序。
     *
     * @param id 挑战
     * @return 行，不锁定，用于确定账户锁顺序
     */
    @Select(
            "SELECT id,phone_digest,purpose,platform,code_digest,state,attempts,expires_at FROM"
                    + " verification_challenge WHERE id=#{id}")
    VerificationEntity find(UUID id);

    /**
     * 返回锁定后的权威状态。
     *
     * @param id 挑战
     * @return 锁定后的权威状态
     */
    @Select(
            "SELECT id,phone_digest,purpose,platform,code_digest,state,attempts,expires_at FROM"
                    + " verification_challenge WHERE id=#{id} FOR UPDATE")
    VerificationEntity lock(UUID id);

    /**
     * 在调用方业务事务内执行条件更新，不单独提交事务。
     *
     * @param id 挑战；失败最多五次，此变更不可因响应401回滚
     */
    @Update("UPDATE verification_challenge SET attempts=attempts+1 WHERE id=#{id} AND attempts<5")
    void failedAttempt(UUID id);

    /**
     * 在调用方业务事务内执行条件更新，不单独提交事务。
     *
     * @param id 已成功校验挑战；必须与业务变更同事务
     */
    @Update(
            "UPDATE verification_challenge SET state='CONSUMED',consumed_at=now() WHERE id=#{id}"
                    + " AND state='READY'")
    void consume(UUID id);

    /**
     * 在调用方业务事务内插入记录，由数据库约束保证唯一性和引用完整性。
     *
     * @param id 学生标识
     * @param name 昵称
     * @param phone 摘要
     * @param masked 脱敏号
     * @param hash 密码摘要
     * @param terms 条款版本
     */
    @Insert(
            "INSERT INTO"
                + " user_account(id,platform,display_name,phone_digest,phone_masked,password_hash,status,terms_version,phone_verified_at,activated_at)"
                + " VALUES(#{id},'student',#{name},#{phone},#{masked},#{hash},'ENABLED',#{terms},now(),now())")
    void student(UUID id, String name, String phone, String masked, String hash, String terms);

    /**
     * 为已注册学生赋予内置SELF角色。
     *
     * @param id 账户标识
     */
    @Insert(
            "INSERT INTO user_role(user_id,role_id)"
                    + " VALUES(#{id},'00000000-0000-4000-8000-000000000002'::uuid)")
    void studentRole(UUID id);

    /**
     * 在调用方业务事务内执行条件更新，不单独提交事务。
     *
     * @param id 用户
     * @param hash 新密码；权限版本递增使全部旧会话失效
     */
    @Update(
            "UPDATE user_account SET"
                + " password_hash=#{hash},auth_version=auth_version+1,version=version+1,updated_at=now()"
                + " WHERE id=#{id}")
    void reset(UUID id, String hash);
}
