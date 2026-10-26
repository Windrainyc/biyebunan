package cn.zhixiang.platform.billing;
import java.util.UUID;
import java.time.OffsetDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import cn.zhixiang.platform.identity.Actor;
import cn.zhixiang.platform.shared.*;
/** 本人词元账单；仅投影数据库确认的用量，不使用Redis推算费用。 */
@Service
public class UsageService {
 private final UsageMapper mapper;
 /** 创建用例。
  * @param mapper 用量查询 */
 public UsageService(UsageMapper mapper){this.mapper=mapper;}
 /** 查询本人所有历史周期用量。
  * @param q 分页过滤
  * @param actor 服务端学生
  * @return 同一数据库快照的分页 */
 @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
 public PageResult<View> list(UsageQuery q,Actor actor){if(!"student".equals(actor.platform()))throw new BusinessException(403,"FORBIDDEN","仅学生可查看本人用量");return new PageResult<>(mapper.list(q,actor.id(),false),q.page(),q.size(),mapper.count(q,actor.id(),false));}
 /** 管理员只读账单，不读取会话正文或引用资料。
  * @param q 分页过滤
  * @param student 可选学生过滤
  * @param actor 管理员身份，入口另校验billing:read
  * @return 账务分页 */
 @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
 public PageResult<View> adminList(UsageQuery q,UUID student,Actor actor){if(!"admin".equals(actor.platform()))throw new BusinessException(403,"FORBIDDEN","仅管理员可查询账单");return new PageResult<>(mapper.list(q,student,true),q.page(),q.size(),mapper.count(q,student,true));}
 /** 账务只读投影，金额为十进制人民币字符串；未确认的实际用量和金额为null。
  * @param id 账单标识，当前与运行ID相同
  * @param runId 运行
  * @param inputTokens 实际输入词元，可空
  * @param outputTokens 实际输出词元，可空
  * @param cachedTokens 输入中的缓存词元，可空
  * @param amount 实际金额；RELEASED为0，未知为null
  * @param adjustmentAmount 累计人工调整净额，退还为负，未调整为0
  * @param netAmount 调整后实际费用，未知为null
  * @param currency 固定CNY
  * @param priceVersionId 不可变价格快照
  * @param state RESERVED/RECONCILING/SETTLED/RELEASED
  * @param createdAt 预占创建时间 */
 public record View(UUID id,UUID runId,Long inputTokens,Long outputTokens,Long cachedTokens,String amount,String adjustmentAmount,String netAmount,String currency,UUID priceVersionId,String state,OffsetDateTime createdAt){}
}
