package cn.zhixiang.platform.identity;
import org.apache.ibatis.annotations.*;
import java.util.UUID;
import java.time.OffsetDateTime;
/** 邀请与MFA登记数据端口；调用者先锁账户，再锁邀请，最后锁挑战。 */
@Mapper
public interface InvitationMapper {
 /** @param id 邀请标识
  * @param user 用户
  * @param digest 一次性令牌摘要
  * @param expiry 截止时间 */
 @Insert("INSERT INTO account_invitation(id,user_id,token_digest,expires_at) VALUES(#{id},#{user},#{digest},#{expiry})") void insert(UUID id,UUID user,String digest,OffsetDateTime expiry);
 /** @param digest 摘要
  * @return 邀请或null，仅供确定锁顺序 */
 @Select("SELECT id,user_id,expires_at,consumed_at FROM account_invitation WHERE token_digest=#{digest}") Row find(String digest);
 /** @param id 邀请
  * @return 锁定后的邀请 */
 @Select("SELECT id,user_id,expires_at,consumed_at FROM account_invitation WHERE id=#{id} FOR UPDATE") Row lock(UUID id);
 /** @param id 邀请；与激活账户同事务 */
 @Update("UPDATE account_invitation SET consumed_at=now() WHERE id=#{id}") void consume(UUID id);
 /** @param id 账户；重新邀请立即使旧链接失效 */
 @Update("UPDATE account_invitation SET consumed_at=now() WHERE user_id=#{id} AND consumed_at IS NULL") void invalidate(UUID id);
 /** @param id 账户
  * @param hash 密码摘要
  * @param name 昵称
  * @param terms 条款版本
  * @param secret MFA引用，中介为空
  * @param step MFA已用时间步，禁止激活后重放 */
 @Update("UPDATE user_account SET password_hash=#{hash},display_name=#{name},terms_version=#{terms},phone_verified_at=CASE WHEN platform='partner' THEN now() ELSE phone_verified_at END,activated_at=now(),status='ENABLED',mfa_secret_ref=#{secret},mfa_last_step=COALESCE(#{step},-1),version=version+1,auth_version=auth_version+1,updated_at=now() WHERE id=#{id} AND activated_at IS NULL") int activate(UUID id,String hash,String name,String terms,String secret,Long step);
 /** @param invitation 邀请
  * @return 关联登记或null */
 @Select("SELECT id,invitation_id,secret_id,expires_at,consumed_at FROM mfa_enrollment WHERE invitation_id=#{invitation}") Enrollment enrollment(UUID invitation);
 /** @param id 登记
  * @param invitation 邀请
  * @param secret 加密种子引用
  * @param expiry 有效时间 */
 @Insert("INSERT INTO mfa_enrollment(id,invitation_id,secret_id,expires_at) VALUES(#{id},#{invitation},#{secret},#{expiry})") void enroll(UUID id,UUID invitation,UUID secret,OffsetDateTime expiry);
 /** @param id 登记；消费与账户激活同事务 */
 @Update("UPDATE mfa_enrollment SET consumed_at=now() WHERE id=#{id}") void consumeEnrollment(UUID id);
 /** @param id 邀请标识
  * @param userId 目标账户
  * @param expiresAt 过期时间
  * @param consumedAt 消费时间，未消费为空 */
 record Row(UUID id,UUID userId,OffsetDateTime expiresAt,OffsetDateTime consumedAt) {}
 /** @param id 登记
  * @param invitationId 邀请
  * @param secretId 加密密钥引用
  * @param expiresAt 截止
  * @param consumedAt 消费时间 */
 record Enrollment(UUID id,UUID invitationId,UUID secretId,OffsetDateTime expiresAt,OffsetDateTime consumedAt) {}
}
