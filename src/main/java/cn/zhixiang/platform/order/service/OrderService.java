package cn.zhixiang.platform.order.service;

import cn.zhixiang.platform.foundation.dto.MajorDtos;
import cn.zhixiang.platform.foundation.dto.SchoolDtos;
import cn.zhixiang.platform.foundation.mapper.MajorMapper;
import cn.zhixiang.platform.foundation.mapper.SchoolMapper;
import cn.zhixiang.platform.identity.model.Actor;
import cn.zhixiang.platform.identity.security.TokenDigester;
import cn.zhixiang.platform.order.dto.OrderDtos;
import cn.zhixiang.platform.order.entity.OrderEntity;
import cn.zhixiang.platform.order.mapper.OrderCancellationMapper;
import cn.zhixiang.platform.order.mapper.OrderMapper;
import cn.zhixiang.platform.shared.dto.PageResult;
import cn.zhixiang.platform.shared.exception.BusinessException;
import cn.zhixiang.platform.shared.mapper.WriteMapper;
import cn.zhixiang.platform.shared.security.SecretCipher;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** 订单用例；草稿编辑和归属变更在行锁、CAS及审计事务内执行。 */
@Service
public class OrderService {
    private final OrderMapper mapper;
    private final OrderCancellationMapper cancellation;
    private final SchoolMapper schools;
    private final MajorMapper majors;
    private final WriteMapper writes;
    private final SecretCipher cipher;
    private final TokenDigester digester;

    /**
     * 构造订单用例。
     *
     * @param cancellation 取消补偿持久层
     * @param mapper 订单数据端口
     * @param schools 院校端口
     * @param majors 专业端口
     * @param writes 审计和outbox
     * @param cipher 隐私加密
     * @param digester 手机摘要
     */
    public OrderService(
            OrderMapper mapper,
            OrderCancellationMapper cancellation,
            SchoolMapper schools,
            MajorMapper majors,
            WriteMapper writes,
            SecretCipher cipher,
            TokenDigester digester) {
        this.mapper = mapper;
        this.cancellation = cancellation;
        this.schools = schools;
        this.majors = majors;
        this.writes = writes;
        this.cipher = cipher;
        this.digester = digester;
    }

    /**
     * 数据范围过滤后分页。
     *
     * @param q 查询
     * @param actor 身份
     * @return 当前页和范围内总数
     */
    @Transactional(
            readOnly = true,
            isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public PageResult<OrderDtos.View> list(OrderDtos.Query q, Actor actor) {
        return new PageResult<>(
                mapper.list(q, actor).stream().map(this::view).toList(),
                q.page(),
                q.size(),
                mapper.count(q, actor));
    }

    /**
     * 查询可见订单，越权与不存在使用相同404。
     *
     * @param id 标识
     * @param actor 身份
     * @return 响应
     * @throws BusinessException 无可见订单
     */
    public OrderDtos.View get(UUID id, Actor actor) {
        var r = mapper.get(id, actor);
        if (r == null) missing();
        return view(r);
    }

    /**
     * 管理员创建草稿。
     *
     * @param w 输入
     * @param actor 身份
     * @return 新订单
     */
    @Transactional(rollbackFor = Exception.class)
    public OrderDtos.View create(OrderDtos.Write w, Actor actor) {
        platform(actor, "admin");
        if (w.source().equals("PARTNER"))
            throw new BusinessException(422, "ORDER_SOURCE_INVALID", "中介来源只允许中介平台创建");
        return createInternal(w, actor, "", w.source());
    }

    /**
     * 中介创建自己名下草稿；价格暂为0，后续须管理员报价和收款确认。
     *
     * @param w 输入
     * @param actor 身份
     * @return 草稿订单
     */
    @Transactional(rollbackFor = Exception.class)
    public OrderDtos.View partnerCreate(OrderDtos.PartnerWrite w, Actor actor) {
        platform(actor, "partner");
        var input =
                new OrderDtos.Write(
                        w.title(),
                        "ZX" + UUID.randomUUID().toString().replace("-", ""),
                        w.studentPhone(),
                        actor.id(),
                        "0",
                        "CNY",
                        w.packageCode(),
                        "MANUAL",
                        w.schoolId(),
                        w.majorId());
        return createInternal(input, actor, w.note(), "PARTNER");
    }

    private OrderDtos.View createInternal(
            OrderDtos.Write w, Actor actor, String note, String source) {
        references(w.schoolId(), w.majorId());
        partner(w.partnerId());
        UUID id = UUID.randomUUID();
        mapper.insert(
                id,
                w,
                actor.id(),
                digester.digest("phone", w.studentPhone()),
                cipher.encrypt("order-phone:" + id, w.studentPhone()),
                note,
                source);
        audit(id, actor.id(), "CREATED", "创建订单草稿");
        return get(id, actor);
    }

    /**
     * 管理员修改未确认且未绑定草稿。
     *
     * @param id 标识
     * @param version 版本
     * @param w 新数据
     * @param actor 身份
     * @return 修改结果
     */
    @Transactional(rollbackFor = Exception.class)
    public OrderDtos.View update(UUID id, long version, OrderDtos.Write w, Actor actor) {
        platform(actor, "admin");
        references(w.schoolId(), w.majorId());
        var old = checked(id, version, actor);
        draft(old);
        if (!old.source().equals(w.source())
                || !old.orderNo().equals(w.orderNo())
                || !java.util.Objects.equals(old.partnerId(), w.partnerId()))
            throw new BusinessException(422, "ORDER_IDENTITY_IMMUTABLE", "来源和订单号不可修改，归属须使用专用接口");
        if (mapper.update(
                        id,
                        version,
                        w,
                        actor.id(),
                        digester.digest("phone", w.studentPhone()),
                        cipher.encrypt("order-phone:" + id, w.studentPhone()))
                != 1) conflict();
        audit(id, actor.id(), "UPDATED", "修改订单草稿");
        return get(id, actor);
    }

    /**
     * 中介仅修改自己名下草稿的专业和备注。
     *
     * @param id 标识
     * @param version 版本
     * @param w 新数据
     * @param actor 身份
     * @return 修改结果
     */
    @Transactional(rollbackFor = Exception.class)
    public OrderDtos.View partnerUpdate(
            UUID id, long version, OrderDtos.PartnerUpdate w, Actor actor) {
        platform(actor, "partner");
        references(w.schoolId(), w.majorId());
        draft(checked(id, version, actor));
        if (mapper.partnerUpdate(id, version, w, actor.id()) != 1) conflict();
        audit(id, actor.id(), "UPDATED", "中介修改草稿资料");
        return get(id, actor);
    }

    /**
     * 调整中介归属；不会更换学生绑定，不授予私聊访问权。
     *
     * @param id 标识
     * @param version 版本
     * @param w 新归属与原因
     * @param actor 管理员
     * @return 结果
     */
    @Transactional(rollbackFor = Exception.class)
    public OrderDtos.View assign(UUID id, long version, OrderDtos.Assignment w, Actor actor) {
        platform(actor, "admin");
        var old = checked(id, version, actor);
        if (java.util.Set.of("CANCELLED", "ARCHIVED").contains(old.status()))
            throw new BusinessException(422, "ORDER_STATE_INVALID", "终止订单不能调整归属");
        partner(w.partnerId());
        if (java.util.Objects.equals(old.partnerId(), w.partnerId())) return view(old);
        if (mapper.assign(id, version, w.partnerId(), actor.id()) != 1) conflict();
        audit(id, actor.id(), "ASSIGNED", w.reason());
        return get(id, actor);
    }

    private void references(UUID school, UUID major) {
        // 与基础数据模块相同顺序：院校 -> 专业 -> 订单；引用保护和启停检查共用锁。
        var s = schools.lock(school);
        var m = majors.lock(major);
        if (s == null
                || m == null
                || !m.schoolId().equals(school)
                || s.status() != SchoolDtos.Status.ENABLED
                || m.status() != MajorDtos.Status.ENABLED)
            throw new BusinessException(422, "ORDER_EDUCATION_INVALID", "请选择同一院校下启用的专业");
    }

    private void partner(UUID id) {
        if (id != null && !mapper.partner(id))
            throw new BusinessException(422, "ORDER_PARTNER_INVALID", "中介必须为已激活的启用账户");
    }

    private OrderEntity checked(UUID id, long version, Actor actor) {
        var r = mapper.lock(id, actor);
        if (r == null) missing();
        if (r.version() != version) conflict();
        return r;
    }

    private void draft(OrderEntity r) {
        if (!r.status().equals("DRAFT")
                || r.studentId() != null
                || !r.receiptStatus().equals("UNCONFIRMED"))
            throw new BusinessException(422, "ORDER_NOT_EDITABLE", "仅未绑定且未确认收款的草稿可编辑");
    }

    private void platform(Actor a, String p) {
        if (!p.equals(a.platform())) throw new BusinessException(403, "FORBIDDEN", "平台无权执行此操作");
    }

    private void missing() {
        throw new BusinessException(404, "RESOURCE_NOT_FOUND", "订单不存在");
    }

    private void conflict() {
        throw new BusinessException(412, "VERSION_CONFLICT", "订单已更新，请刷新重试");
    }

    private void audit(UUID id, UUID actor, String action, String reason) {
        writes.auditReason(UUID.randomUUID(), actor, action, "software_order", id, reason);
        writes.event(UUID.randomUUID(), id, "software_order." + action, "{\"id\":\"" + id + "\"}");
    }

    private OrderDtos.View view(OrderEntity r) {
        return new OrderDtos.View(
                r.id(),
                r.version(),
                r.createdAt(),
                r.updatedAt(),
                r.orderNo(),
                r.title(),
                r.studentId(),
                r.partnerId(),
                r.amount(),
                r.currency(),
                r.status(),
                r.receiptStatus(),
                r.packageCode(),
                r.schoolId(),
                r.majorId(),
                r.source(),
                r.note(),
                r.status().equals("CANCELLED") && r.receiptStatus().equals("CONFIRMED"));
    }

    /**
     * 按状态机流转；确认是人工核实，不能等同收款。取消先阻止新运行，再检查未决账务并暂停项目；收款历史保留。
     *
     * @param id 订单
     * @param version CAS版本
     * @param w 动作及原因
     * @param actor 管理员
     * @return 新状态
     * @throws BusinessException 状态前置条件不满足
     */
    @Transactional(rollbackFor = Exception.class)
    public OrderDtos.View transition(UUID id, long version, OrderDtos.Transition w, Actor actor) {
        platform(actor, "admin");
        var old = checked(id, version, actor);
        String next =
                switch (w.action()) {
                    case "CONFIRM" -> {
                        requireState(old, "DRAFT");
                        yield "CONFIRMED";
                    }
                    case "ACTIVATE" -> {
                        requireState(old, "CONFIRMED");
                        if (old.studentId() == null
                                || !old.receiptStatus().equals("CONFIRMED")
                                || mapper.projectStage(id) == null)
                            throw new BusinessException(
                                    422, "ORDER_ACTIVATION_BLOCKED", "需完成学生绑定、收款确认并建立项目后才能启动");
                        yield "ACTIVE";
                    }
                    case "COMPLETE" -> {
                        requireState(old, "ACTIVE");
                        if (!"DONE".equals(mapper.projectStage(id)))
                            throw new BusinessException(422, "PROJECT_NOT_ACCEPTED", "项目尚未验收完成");
                        yield "COMPLETED";
                    }
                    case "CANCEL" -> {
                        if (!java.util.Set.of("DRAFT", "CONFIRMED", "ACTIVE")
                                .contains(old.status()))
                            throw new BusinessException(422, "ORDER_CANCEL_BLOCKED", "当前订单状态不允许取消");
                        // 创建运行也先锁订单；持有此锁后不会再出现新的预占。
                        if (cancellation.unsettled(id))
                            throw new BusinessException(
                                    422, "ORDER_CANCEL_UNSETTLED", "请先处理运行中的答疑及待核对费用");
                        cancellation.pause(id, actor.id(), w.reason());
                        cancellation.invalidate(id);
                        cancellation.record(
                                id,
                                old.status(),
                                old.receiptStatus().equals("CONFIRMED"),
                                actor.id(),
                                w.reason());
                        yield "CANCELLED";
                    }
                    case "ARCHIVE" -> {
                        requireState(old, "COMPLETED");
                        yield "ARCHIVED";
                    }
                    default -> throw new BusinessException(422, "ORDER_ACTION_INVALID", "订单动作无效");
                };
        if (mapper.transition(id, version, next, actor.id()) != 1) conflict();
        audit(id, actor.id(), "TRANSITION_" + next, w.reason());
        return get(id, actor);
    }

    private void requireState(OrderEntity r, String expected) {
        if (!r.status().equals(expected))
            throw new BusinessException(422, "ORDER_STATE_INVALID", "订单状态不允许此动作");
    }
}
