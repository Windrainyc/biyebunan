package cn.zhixiang.platform.order;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import cn.zhixiang.platform.shared.*;
import cn.zhixiang.platform.identity.*;
import cn.zhixiang.platform.foundation.*;
/** 订单用例；草稿编辑和归属变更在行锁、CAS及审计事务内执行。 */
@Service
public class OrderService {
 private final OrderMapper mapper;private final SchoolMapper schools;private final MajorMapper majors;private final WriteMapper writes;private final SecretCipher cipher;private final TokenDigester digester;
 /** 构造订单用例。
 * @param mapper 订单数据端口
 * @param schools 院校端口
 * @param majors 专业端口
 * @param writes 审计和outbox
 * @param cipher 隐私加密
 * @param digester 手机摘要 */
 public OrderService(OrderMapper mapper,SchoolMapper schools,MajorMapper majors,WriteMapper writes,SecretCipher cipher,TokenDigester digester){this.mapper=mapper;this.schools=schools;this.majors=majors;this.writes=writes;this.cipher=cipher;this.digester=digester;}
 /** 数据范围过滤后分页。
 * @param q 查询
 * @param actor 身份
 * @return 当前页和范围内总数 */
 @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
 public PageResult<OrderDtos.View> list(OrderDtos.Query q,Actor actor){return new PageResult<>(mapper.list(q,actor).stream().map(this::view).toList(),q.page(),q.size(),mapper.count(q,actor));}
 /** 查询可见订单，越权与不存在使用相同404。
 * @param id 标识
 * @param actor 身份
 * @return 响应
 * @throws BusinessException 无可见订单 */
 public OrderDtos.View get(UUID id,Actor actor){var r=mapper.get(id,actor);if(r==null)missing();return view(r);}
 /** 管理员创建草稿。
 * @param w 输入
 * @param actor 身份
 * @return 新订单 */
 @Transactional(rollbackFor=Exception.class)
 public OrderDtos.View create(OrderDtos.Write w,Actor actor){platform(actor,"admin");if(w.source().equals("PARTNER"))throw new BusinessException(422,"ORDER_SOURCE_INVALID","中介来源只允许中介平台创建");return createInternal(w,actor,"",w.source());}
 /** 中介创建自己名下草稿；价格暂为0，后续须管理员报价和收款确认。
 * @param w 输入
 * @param actor 身份
 * @return 草稿订单 */
 @Transactional(rollbackFor=Exception.class)
 public OrderDtos.View partnerCreate(OrderDtos.PartnerWrite w,Actor actor){platform(actor,"partner");var input=new OrderDtos.Write(w.title(),"ZX"+UUID.randomUUID().toString().replace("-",""),w.studentPhone(),actor.id(),"0","CNY",w.packageCode(),"MANUAL",w.schoolId(),w.majorId());return createInternal(input,actor,w.note(),"PARTNER");}
 private OrderDtos.View createInternal(OrderDtos.Write w,Actor actor,String note,String source){references(w.schoolId(),w.majorId());partner(w.partnerId());UUID id=UUID.randomUUID();mapper.insert(id,w,actor.id(),digester.digest("phone",w.studentPhone()),cipher.encrypt("order-phone:"+id,w.studentPhone()),note,source);audit(id,actor.id(),"CREATED","创建订单草稿");return get(id,actor);}
 /** 管理员修改未确认且未绑定草稿。
 * @param id 标识
 * @param version 版本
 * @param w 新数据
 * @param actor 身份
 * @return 修改结果 */
 @Transactional(rollbackFor=Exception.class)
 public OrderDtos.View update(UUID id,long version,OrderDtos.Write w,Actor actor){platform(actor,"admin");references(w.schoolId(),w.majorId());var old=checked(id,version,actor);draft(old);if(!old.source().equals(w.source())||!old.orderNo().equals(w.orderNo())||!java.util.Objects.equals(old.partnerId(),w.partnerId()))throw new BusinessException(422,"ORDER_IDENTITY_IMMUTABLE","来源和订单号不可修改，归属须使用专用接口");if(mapper.update(id,version,w,actor.id(),digester.digest("phone",w.studentPhone()),cipher.encrypt("order-phone:"+id,w.studentPhone()))!=1)conflict();audit(id,actor.id(),"UPDATED","修改订单草稿");return get(id,actor);}
 /** 中介仅修改自己名下草稿的专业和备注。
 * @param id 标识
 * @param version 版本
 * @param w 新数据
 * @param actor 身份
 * @return 修改结果 */
 @Transactional(rollbackFor=Exception.class)
 public OrderDtos.View partnerUpdate(UUID id,long version,OrderDtos.PartnerUpdate w,Actor actor){platform(actor,"partner");references(w.schoolId(),w.majorId());draft(checked(id,version,actor));if(mapper.partnerUpdate(id,version,w,actor.id())!=1)conflict();audit(id,actor.id(),"UPDATED","中介修改草稿资料");return get(id,actor);}
 /** 调整中介归属；不会更换学生绑定，不授予私聊访问权。
 * @param id 标识
 * @param version 版本
 * @param w 新归属与原因
 * @param actor 管理员
 * @return 结果 */
 @Transactional(rollbackFor=Exception.class)
 public OrderDtos.View assign(UUID id,long version,OrderDtos.Assignment w,Actor actor){platform(actor,"admin");var old=checked(id,version,actor);if(java.util.Set.of("CANCELLED","ARCHIVED").contains(old.status()))throw new BusinessException(422,"ORDER_STATE_INVALID","终止订单不能调整归属");partner(w.partnerId());if(java.util.Objects.equals(old.partnerId(),w.partnerId()))return view(old);if(mapper.assign(id,version,w.partnerId(),actor.id())!=1)conflict();audit(id,actor.id(),"ASSIGNED",w.reason());return get(id,actor);}
 private void references(UUID school,UUID major){
  // 与基础数据模块相同顺序：院校 -> 专业 -> 订单；引用保护和启停检查共用锁。
  var s=schools.lock(school);var m=majors.lock(major);
  if(s==null||m==null||!m.schoolId().equals(school)||s.status()!=SchoolDtos.Status.ENABLED||m.status()!=MajorDtos.Status.ENABLED)throw new BusinessException(422,"ORDER_EDUCATION_INVALID","请选择同一院校下启用的专业");
 }
 private void partner(UUID id){if(id!=null&&!mapper.partner(id))throw new BusinessException(422,"ORDER_PARTNER_INVALID","中介必须为已激活的启用账户");}
 private OrderMapper.Row checked(UUID id,long version,Actor actor){var r=mapper.lock(id,actor);if(r==null)missing();if(r.version()!=version)conflict();return r;}
 private void draft(OrderMapper.Row r){if(!r.status().equals("DRAFT")||r.studentId()!=null||!r.receiptStatus().equals("UNCONFIRMED"))throw new BusinessException(422,"ORDER_NOT_EDITABLE","仅未绑定且未确认收款的草稿可编辑");}
 private void platform(Actor a,String p){if(!p.equals(a.platform()))throw new BusinessException(403,"FORBIDDEN","平台无权执行此操作");}
 private void missing(){throw new BusinessException(404,"RESOURCE_NOT_FOUND","订单不存在");}
 private void conflict(){throw new BusinessException(412,"VERSION_CONFLICT","订单已更新，请刷新重试");}
 private void audit(UUID id,UUID actor,String action,String reason){writes.auditReason(UUID.randomUUID(),actor,action,"software_order",id,reason);writes.event(UUID.randomUUID(),id,"software_order."+action,"{\"id\":\""+id+"\"}");}
 private OrderDtos.View view(OrderMapper.Row r){return new OrderDtos.View(r.id(),r.version(),r.createdAt(),r.updatedAt(),r.orderNo(),r.title(),r.studentId(),r.partnerId(),r.amount(),r.currency(),r.status(),r.receiptStatus(),r.packageCode(),r.schoolId(),r.majorId(),r.source(),r.note());}
}
