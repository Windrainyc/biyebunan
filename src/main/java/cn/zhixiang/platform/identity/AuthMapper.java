package cn.zhixiang.platform.identity;
import java.util.*;
import java.time.OffsetDateTime;
import org.apache.ibatis.annotations.*;
/** 认证持久化访问；锁顺序固定为账户后会话，避免刷新与停用并发死锁。 */
@Mapper
public interface AuthMapper {
 /** @param platform 路径确定的平台 @param phone 手机号摘要 @return 账户，可空 */
 @Select("SELECT id,version,platform,display_name,phone_masked,password_hash,status,auth_version,school_id,major_id,mfa_secret_ref,created_at,updated_at FROM user_account WHERE platform=#{platform} AND phone_digest=#{phone}")
 Account find(String platform,String phone);
 /** @param id 账户标识 @return 加锁账户 */
 @Select("SELECT id,version,platform,display_name,phone_masked,password_hash,status,auth_version,school_id,major_id,mfa_secret_ref,created_at,updated_at FROM user_account WHERE id=#{id} FOR UPDATE")
 Account lockAccount(UUID id);
 /** @param id 账户标识 @return 当前角色列表 */
 @Select("SELECT role_id FROM user_role WHERE user_id=#{id}") List<UUID> roles(UUID id);
 /** @param r 新会话，全部令牌为摘要 */
 @Insert("INSERT INTO auth_session(id,user_id,access_digest,refresh_digest,csrf_digest,auth_version,expires_at,refresh_expires_at,family_id) VALUES(#{id},#{userId},#{accessDigest},#{refreshDigest},#{csrfDigest},#{authVersion},#{expiresAt},#{refreshExpiresAt},#{familyId})")
 void insert(SessionRow r);
 /** @param digest 刷新摘要 @return 会话，无锁定位账户后再依次加锁 */
 @Select("SELECT id,user_id,access_digest,refresh_digest,csrf_digest,auth_version,expires_at,refresh_expires_at,family_id,consumed_at,revoked_at FROM auth_session WHERE refresh_digest=#{digest}")
 SessionRow findRefresh(String digest);
 /** @param id 会话标识 @return 锁定最新会话状态 */
 @Select("SELECT id,user_id,access_digest,refresh_digest,csrf_digest,auth_version,expires_at,refresh_expires_at,family_id,consumed_at,revoked_at FROM auth_session WHERE id=#{id} FOR UPDATE")
 SessionRow lockSession(UUID id);
 /** @param id 旧会话标识；消费同时撤销旧 access */
 @Update("UPDATE auth_session SET consumed_at=now(),revoked_at=now() WHERE id=#{id}") void consume(UUID id);
 /** @param family 令牌族；重放或登出撤销族内所有会话 */
 @Update("UPDATE auth_session SET revoked_at=COALESCE(revoked_at,now()) WHERE family_id=#{family}") void revoke(UUID family);
 /** @param id 账户 @param step 已验证时间步 @return 1 表示首次消费，0 表示重放 */
 @Update("UPDATE user_account SET mfa_last_step=#{step} WHERE id=#{id} AND mfa_last_step<#{step}")
 int consumeMfa(UUID id,long step);
 /** @param bucket 端和手机号或IP摘要 @return 当前十分钟窗口内请求次数 */
 @Select("""
 INSERT INTO auth_attempt_window(bucket,window_start,attempts) VALUES(#{bucket},now(),1)
 ON CONFLICT(bucket) DO UPDATE SET
 attempts=CASE WHEN auth_attempt_window.window_start<now()-interval '10 minutes' THEN 1 ELSE auth_attempt_window.attempts+1 END,
 window_start=CASE WHEN auth_attempt_window.window_start<now()-interval '10 minutes' THEN now() ELSE auth_attempt_window.window_start END
 RETURNING attempts
 """) int attempt(String bucket);
 /** 内部账户投影，不得作为 HTTP 返回值。
  * @param id 标识 @param version 版本 @param platform 平台 @param displayName 昵称 @param phoneMasked 脱敏电话
  * @param passwordHash 密码摘要 @param status 状态 @param authVersion 权限版本 @param schoolId 院校 @param majorId 专业
  * @param mfaSecretRef MFA密钥引用 @param createdAt 创建时间 @param updatedAt 更新时间 */
 record Account(UUID id,long version,String platform,String displayName,String phoneMasked,String passwordHash,String status,long authVersion,UUID schoolId,UUID majorId,String mfaSecretRef,OffsetDateTime createdAt,OffsetDateTime updatedAt) {}
 /** 内部会话行；摘要不对外公开。
  * @param id 会话 @param userId 用户 @param accessDigest 访问摘要 @param refreshDigest 刷新摘要 @param csrfDigest CSRF摘要
  * @param authVersion 权限版本 @param expiresAt 访问过期 @param refreshExpiresAt 刷新过期 @param familyId 令牌族
  * @param consumedAt 已轮换时间 @param revokedAt 撤销时间 */
 record SessionRow(UUID id,UUID userId,String accessDigest,String refreshDigest,String csrfDigest,long authVersion,OffsetDateTime expiresAt,OffsetDateTime refreshExpiresAt,UUID familyId,OffsetDateTime consumedAt,OffsetDateTime revokedAt) {}
}
