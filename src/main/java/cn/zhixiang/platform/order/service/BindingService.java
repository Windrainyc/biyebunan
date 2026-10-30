package cn.zhixiang.platform.order.service;

import cn.zhixiang.platform.identity.dto.VerificationDtos;
import cn.zhixiang.platform.identity.mapper.AuthMapper;
import cn.zhixiang.platform.identity.mapper.IdentityMapper;
import cn.zhixiang.platform.identity.model.Actor;
import cn.zhixiang.platform.identity.port.OrderChallengePort;
import cn.zhixiang.platform.identity.port.SmsGateway;
import cn.zhixiang.platform.identity.security.TokenDigester;
import cn.zhixiang.platform.order.dto.BindingDtos;
import cn.zhixiang.platform.order.dto.OrderDtos;
import cn.zhixiang.platform.order.mapper.BindingMapper;
import cn.zhixiang.platform.shared.exception.BusinessException;
import cn.zhixiang.platform.shared.mapper.WriteMapper;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** 一次性订单证明：错误以结果值返回，使限次提交；成功消费与归属变更原子提交。 */
@Service
public class BindingService implements OrderChallengePort {
    private final BindingMapper mapper;
    private final IdentityMapper identity;
    private final AuthMapper auth;
    private final TokenDigester digester;
    private final SmsGateway sms;
    private final TransactionTemplate tx;
    private final WriteMapper writes;
    private final cn.zhixiang.platform.notification.service.NotificationService notifications;
    private final OrderService orders;
    private final SecureRandom random = new SecureRandom();

    /**
     * 创建业务组件。
     *
     * @param mapper 挑战数据
     * @param identity 会话权威校验
     * @param auth 限流端口
     * @param digester HMAC摘要
     * @param sms 短信端口
     * @param tx 短事务
     * @param notifications 事务内通知快照
     * @param writes 审计事件
     * @param orders 订单读取
     */
    public BindingService(
            BindingMapper mapper,
            IdentityMapper identity,
            AuthMapper auth,
            TokenDigester digester,
            SmsGateway sms,
            TransactionTemplate tx,
            cn.zhixiang.platform.notification.service.NotificationService notifications,
            WriteMapper writes,
            OrderService orders) {
        this.mapper = mapper;
        this.identity = identity;
        this.auth = auth;
        this.digester = digester;
        this.sms = sms;
        this.tx = tx;
        this.writes = writes;
        this.notifications = notifications;
        this.orders = orders;
    }

    /**
     * 发起绑定挑战，短信在数据库事务外调用。
     *
     * @param input 指定手机号与订单
     * @param actor 已认证学生
     * @param ip 来源
     * @return 已发送挑战
     * @throws BusinessException 会话或归属无效、限流、短信不可用
     */
    @Override
    public VerificationDtos.Challenge challenge(
            VerificationDtos.ChallengeRequest input, Actor actor, String ip) {
        if (actor == null || !"student".equals(actor.platform()))
            throw new BusinessException(401, "SESSION_REQUIRED", "请先登录学生平台");
        if (input.purpose() != VerificationDtos.Purpose.BIND_ORDER
                || input.orderNo() == null
                || input.orderNo().isBlank()
                || (input.platform() != null && !input.platform().equals("student")))
            throw new BusinessException(400, "VALIDATION_ERROR", "绑定挑战须指定订单且仅适用学生端");
        sms.requireConfigured();
        if (auth.attempt(digester.digest("sms-ip", ip)) > 30
                || auth.attempt(digester.digest("sms-phone", input.phone())) > 3)
            throw new BusinessException(429, "RATE_LIMITED", "验证码发送过多，请稍后重试");
        UUID id = UUID.randomUUID();
        String code = String.format(Locale.ROOT, "%06d", random.nextInt(1000000));
        String phone = digester.digest("phone", input.phone());
        var expiry =
                tx.execute(
                        s -> {
                            Long version = identity.lockVersion(actor.id());
                            if (version == null
                                    || version != actor.authVersion()
                                    || identity.activeSession(actor.sessionId()) != 1)
                                throw invalid();
                            UUID order = mapper.eligible(input.orderNo(), actor.id(), phone);
                            if (order == null) throw invalid();
                            return mapper.insert(
                                    id,
                                    order,
                                    actor.id(),
                                    phone,
                                    digester.digest("order-challenge:" + id, code));
                        });
        try {
            sms.send(id, input.phone(), code, VerificationDtos.Purpose.BIND_ORDER);
            mapper.sent(id, "READY");
        } catch (RuntimeException e) {
            mapper.sent(id, "FAILED");
            throw e;
        }
        return new VerificationDtos.Challenge(id, expiry, 600);
    }

    /**
     * 消费证明并绑定；调用方必须在IdempotentWrites账户锁事务内调用。
     *
     * @param input 订单与挑战验证码
     * @param actor 服务端学生
     * @return 已绑定订单，验证失败返回null以提交错误次数
     * @throws BusinessException 非学生调用
     */
    @Transactional(rollbackFor = Exception.class)
    public OrderDtos.View bind(BindingDtos.Write input, Actor actor) {
        if (!"student".equals(actor.platform()))
            throw new BusinessException(403, "FORBIDDEN", "仅学生可绑定订单");
        String phone = mapper.phone(actor.id());
        if (phone == null) return null;
        UUID order = mapper.eligible(input.orderNo(), actor.id(), phone);
        if (order == null) return null;
        var proof = mapper.lock(input.challengeId());
        if (proof == null
                || !proof.orderId().equals(order)
                || !proof.actorId().equals(actor.id())
                || !proof.phoneDigest().equals(phone)
                || !proof.state().equals("READY")
                || !proof.alive()
                || proof.attempts() >= 5) return null;
        if (!MessageDigest.isEqual(
                proof.codeDigest().getBytes(StandardCharsets.US_ASCII),
                digester.digest("order-challenge:" + input.challengeId(), input.code())
                        .getBytes(StandardCharsets.US_ASCII))) {
            mapper.failed(input.challengeId());
            return null;
        }
        mapper.consume(input.challengeId());
        if (mapper.bind(order, actor.id()) == 1) {
            writes.audit(UUID.randomUUID(), actor.id(), "BOUND", "software_order", order);
            UUID event = UUID.randomUUID();
            writes.event(event, order, "software_order.BOUND", "{\"id\":\"" + order + "\"}");
            notifications.enqueue(actor.id(), event, "订单绑定成功", "订单已关联到你的账户，可在我的项目中查看后续进展。");
        }
        return orders.get(order, actor);
    }

    private BusinessException invalid() {
        return new BusinessException(401, "VERIFICATION_INVALID", "验证材料无效或订单不可绑定");
    }
}
