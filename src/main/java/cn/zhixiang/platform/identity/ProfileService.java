package cn.zhixiang.platform.identity;
import java.util.UUID;
import jakarta.validation.constraints.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import cn.zhixiang.platform.shared.*;
/** 学生/中介本人资料用例，只允许修改展示和教育资料，不更改权限或归属。 */
@Service
public class ProfileService {
 private final ProfileMapper mapper;private final UserService users;private final AuthMapper accounts;private final WriteMapper writes;
 /** 创建用例。
  * @param mapper 资料端口
  * @param users 脱敏投影
  * @param accounts 权威账户锁
  * @param writes 审计 */
 public ProfileService(ProfileMapper mapper,UserService users,AuthMapper accounts,WriteMapper writes){this.mapper=mapper;this.users=users;this.accounts=accounts;this.writes=writes;}
 /** 获取本人的脱敏资料。
  * @param actor 当前身份
  * @param platform 由入口固定的平台
  * @return 只读资料 */
 public AuthDtos.User get(Actor actor,String platform){check(actor,platform);return users.get(actor.id());}
 /** CAS更新资料；保持authVersion不变，昵称变化不吊销当前会话。
  * @param actor 当前身份
  * @param platform 固定平台
  * @param version 强ETag版本
  * @param w 输入
  * @return 更新后的脱敏资料 */
 @Transactional(rollbackFor=Exception.class)
 public AuthDtos.User update(Actor actor,String platform,long version,Write w){
  check(actor,platform);var account=accounts.lockAccount(actor.id());if(account==null||!account.platform().equals(platform))throw new BusinessException(404,"RESOURCE_NOT_FOUND","账户不存在");
  if(account.version()!=version)throw new BusinessException(412,"VERSION_CONFLICT","资料已经更新");
  // 按院校→专业锁定，阻止检验成功后并发停用或删除；不存在则拒绝。
  if(mapper.school(w.schoolId())==null||mapper.major(w.majorId(),w.schoolId())==null)throw new BusinessException(422,"INVALID_REFERENCE","院校专业须启用且相互匹配");
  if(mapper.update(actor.id(),version,w)!=1)throw new BusinessException(412,"VERSION_CONFLICT","资料已经更新");
  writes.audit(UUID.randomUUID(),actor.id(),"PROFILE_UPDATED","user",actor.id());return get(actor,platform);
 }
 private void check(Actor actor,String platform){if(!platform.equals(actor.platform())||!(platform.equals("student")||platform.equals("partner")))throw new BusinessException(403,"FORBIDDEN","平台不匹配");}
 /** 本人资料输入，没有角色、状态、密码或中介归属字段。
  * @param displayName 昵称1至200字符
  * @param schoolId 启用院校
  * @param majorId 该院校下启用专业 */
 public record Write(@NotBlank @Size(max=200) String displayName,@NotNull UUID schoolId,@NotNull UUID majorId){}
}
