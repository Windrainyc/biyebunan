package cn.zhixiang.platform.identity;
import java.util.UUID;
import java.util.List;
import org.apache.ibatis.annotations.*;
/** 身份数据只读访问；每请求验证停用、撤销、过期以及权限版本。 */
@Mapper
public interface IdentityMapper {
    /** @param digest access HMAC @return 当前合法身份，不满足全部条件时为空 */
    @Select("""
        SELECT u.id,u.platform FROM auth_session s JOIN user_account u ON u.id=s.user_id
        WHERE s.access_digest=#{digest} AND s.revoked_at IS NULL AND s.expires_at>now()
          AND u.status='ENABLED' AND u.auth_version=s.auth_version
        """)
    Actor authenticate(String digest);
    /** @param id 账户 ID @return 仅匹配账户平台且启用角色的权限码 */
    @Select("""
        SELECT DISTINCT rp.permission_code FROM user_role ur JOIN role r ON r.id=ur.role_id
        JOIN user_account u ON u.id=ur.user_id JOIN role_permission rp ON rp.role_id=r.id
        WHERE u.id=#{id} AND r.platform=u.platform AND r.status='ENABLED'
        """)
    List<String> permissions(UUID id);
}
