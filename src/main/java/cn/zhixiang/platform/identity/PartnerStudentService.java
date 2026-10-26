package cn.zhixiang.platform.identity;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import cn.zhixiang.platform.shared.*;
/** 中介学生读取用例，不允许跨中介或读取学生私聊。 */
@Service
public class PartnerStudentService {
 private final PartnerStudentMapper mapper;
 /** 创建用例。
  * @param mapper 范围查询 */
 public PartnerStudentService(PartnerStudentMapper mapper){this.mapper=mapper;}
 /** 当前中介学生分页。
  * @param q 过滤
  * @param actor 中介身份
  * @return 分页 */
 @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
 public PageResult<View> list(PageQuery q,Actor actor){check(actor);return new PageResult<>(mapper.list(q,actor.id()),q.page(),q.size(),mapper.count(q,actor.id()));}
 /** 当前中介学生详情，范围外统一404。
  * @param id 学生
  * @param actor 中介身份
  * @return 最小资料 */
 public View get(UUID id,Actor actor){check(actor);var row=mapper.get(id,actor.id());if(row==null)throw new BusinessException(404,"RESOURCE_NOT_FOUND","学生不存在");return row;}
 private void check(Actor actor){if(!"partner".equals(actor.platform()))throw new BusinessException(403,"FORBIDDEN","仅中介可查看名下学生");}
 /** 中介可见学生摘要。
  * @param id 学生ID
  * @param displayName 昵称
  * @param phoneMasked 脱敏手机号
  * @param schoolName 个人资料院校名，未设置时null
  * @param activeOrderCount 本中介下CONFIRMED/ACTIVE订单数 */
 public record View(UUID id,String displayName,String phoneMasked,String schoolName,long activeOrderCount){}
}
