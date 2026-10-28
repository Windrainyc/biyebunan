package cn.zhixiang.platform.billing.service;

import cn.zhixiang.platform.billing.dto.QuotaResponse;
import cn.zhixiang.platform.billing.entity.QuotaAccountEntity;
import cn.zhixiang.platform.billing.entity.QuotaBenefitEntity;
import cn.zhixiang.platform.billing.entity.QuotaHoldEntity;
import cn.zhixiang.platform.billing.mapper.QuotaMapper;
import cn.zhixiang.platform.model.mapper.PriceMapper;
import cn.zhixiang.platform.shared.exception.BusinessException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;

/** 数据库额度与账本用例；外部模型调用不得置于这些事务中。 */
@Service
public class QuotaService {
    private static final BigDecimal ZERO = new BigDecimal("0.000000");
    private final QuotaMapper mapper;
    private final PriceMapper prices;

    /**
     * 创建用例。
     *
     * @param mapper 额度端口
     * @param prices 不可变生效价格
     */
    public QuotaService(QuotaMapper mapper, PriceMapper prices) {
        this.mapper = mapper;
        this.prices = prices;
    }

    /**
     * 当前权益额度，首次访问按固定会员快照幂等授予；撤销后可用为0，既有冻结仍显示。
     *
     * @param user 学生
     * @return 额度
     */
    @Transactional(rollbackFor = Exception.class)
    public QuotaResponse current(UUID user) {
        var state = prepare(user);
        var a = state.account();
        var b = state.benefit();
        return new QuotaResponse(
                b == null ? 0 : availableTokens(a),
                a.reservedTokens(),
                b == null ? ZERO.toPlainString() : availableAmount(a).toPlainString(),
                a.reservedAmount().toPlainString(),
                "CNY",
                b != null && b.expiresAt().isBefore(a.periodEnd()) ? b.expiresAt() : a.periodEnd());
    }

    /**
     * 按唯一运行ID预占，所有节点由账户行锁串行裁决。
     *
     * @param user 学生
     * @param run 唯一运行
     * @param price 固定价格版本
     * @param tokens 保守词元上限
     * @param amount 保守费用上限
     * @return 预占状态
     * @throws BusinessException 无权益、余额不足或同ID参数冲突
     */
    @Transactional(rollbackFor = Exception.class)
    public QuotaHoldEntity reserve(
            UUID user, UUID run, UUID price, long tokens, BigDecimal amount) {
        if (tokens <= 0 || amount.signum() < 0 || amount.scale() > 6)
            throw new BusinessException(422, "RESERVATION_INVALID", "预占值不合法");
        if (mapper.user(user) == null) throw new BusinessException(403, "FORBIDDEN", "学生账户不可用");
        if (!mapper.runMatches(run, user, price))
            throw new BusinessException(404, "RESOURCE_NOT_FOUND", "运行或价格归属不匹配");
        var old = mapper.hold(run);
        if (old != null) {
            if (!user.equals(mapper.owner(run))
                    || !old.priceId().equals(price)
                    || old.tokens() != tokens
                    || old.amount().compareTo(amount) != 0)
                throw new BusinessException(409, "RESERVATION_CONFLICT", "运行预占参数不一致");
            return old;
        }
        var state = prepare(user);
        var a = state.account();
        if (state.benefit() == null)
            throw new BusinessException(422, "MEMBERSHIP_REQUIRED", "当前没有有效会员权益");
        if (availableTokens(a) < tokens || availableAmount(a).compareTo(amount) < 0)
            throw new BusinessException(422, "QUOTA_EXCEEDED", "可用额度不足");
        mapper.reserve(run, a.id(), price, tokens, amount);
        delta(a.id(), run, "RESERVE", 0, tokens, 0, ZERO, amount, ZERO);
        return mapper.hold(run);
    }

    /**
     * 根据供应商实际用量结算；超出预占仍完整记账，可用额度降至0并留待成本核查。
     *
     * @param run 运行
     * @param input 输入词元（含缓存）
     * @param output 输出词元
     * @param cached 缓存输入词元
     * @return 已结算预占
     */
    @Transactional(rollbackFor = Exception.class)
    public QuotaHoldEntity settle(UUID run, long input, long output, long cached) {
        var h = locked(run);
        var p = prices.get(h.priceId());
        BigDecimal cost =
                BillingMath.cost(
                        input,
                        output,
                        cached,
                        new BigDecimal(p.inputPerMillion()),
                        new BigDecimal(p.outputPerMillion()),
                        new BigDecimal(p.cachedInputPerMillion()));
        long tokens;
        try {
            tokens = Math.addExact(input, output);
        } catch (ArithmeticException e) {
            throw new BusinessException(422, "USAGE_INVALID", "词元用量溢出");
        }
        if (h.state().equals("SETTLED")) {
            if (h.inputTokens() != input
                    || h.outputTokens() != output
                    || h.cachedTokens() != cached)
                throw new BusinessException(409, "SETTLEMENT_CONFLICT", "重复结算用量不一致");
            return h;
        }
        if (h.state().equals("RELEASED"))
            throw new BusinessException(409, "RESERVATION_RELEASED", "已释放预占不能再次扣费");
        delta(
                h.accountId(),
                run,
                "SETTLE",
                0,
                -h.tokens(),
                tokens,
                ZERO,
                h.amount().negate(),
                cost);
        mapper.state(run, "SETTLED", input, output, cached, cost);
        return mapper.hold(run);
    }

    /**
     * 双人复核批准后追加原调用更正，原预占和结算记录不可覆盖。 调用者先锁账户、运行及裁决单；本方法再锁预占，不调用供应商。
     *
     * @param run 原运行，历史月份仍记原账户
     * @param input 可信输入，null表示明确不收费
     * @param output 可信输出，与input同空
     * @param cached 缓存输入，与input同空
     * @throws BusinessException 已更正、未结算、字段非法或人工退费导致净费用为负
     */
    @Transactional(rollbackFor = Exception.class)
    public void correct(UUID run, Long input, Long output, Long cached) {
        var h = locked(run);
        if (!Set.of("SETTLED", "RELEASED").contains(h.state()))
            throw new BusinessException(409, "CORRECTION_NOT_AVAILABLE", "仅可更正已结束账务");
        if (mapper.corrected(run))
            throw new BusinessException(409, "CORRECTION_ALREADY_APPLIED", "更正流水已经存在");
        if ((input == null) != (output == null) || (input == null) != (cached == null))
            throw new BusinessException(422, "USAGE_INVALID", "用量字段必须同时提供或同时为空");
        var p = prices.get(h.priceId());
        BigDecimal cost =
                input == null
                        ? ZERO
                        : BillingMath.cost(
                                input,
                                output,
                                cached,
                                new BigDecimal(p.inputPerMillion()),
                                new BigDecimal(p.outputPerMillion()),
                                new BigDecimal(p.cachedInputPerMillion()));
        long target;
        try {
            target = input == null ? 0 : Math.addExact(input, output);
        } catch (ArithmeticException ex) {
            throw new BusinessException(422, "USAGE_INVALID", "词元用量溢出");
        }
        long original =
                "SETTLED".equals(h.state()) ? Math.addExact(h.inputTokens(), h.outputTokens()) : 0;
        BigDecimal originalCost = "SETTLED".equals(h.state()) ? h.actualAmount() : ZERO;
        // 已有人工优惠不能使更正后净费用为负；由独立金额调整先处理冲突，禁止静默吞掉退款。
        if (cost.add(mapper.netAdjustments(run)).signum() < 0)
            throw new BusinessException(
                    409, "CORRECTION_ADJUSTMENT_CONFLICT", "现有人工退费与更正冲突，请先复核金额调整");
        delta(
                h.accountId(),
                run,
                "CORRECTION",
                0,
                0,
                target - original,
                ZERO,
                ZERO,
                cost.subtract(originalCost));
    }

    /**
     * 不确定是否已发生费用时保留冻结，等待核对；不自动重发供应商请求。
     *
     * @param run 运行
     */
    @Transactional(rollbackFor = Exception.class)
    public void unknown(UUID run) {
        var h = locked(run);
        if (!h.state().equals("RESERVED")) return;
        delta(h.accountId(), run, "RECONCILING", 0, 0, 0, ZERO, ZERO, ZERO);
        mapper.state(run, "RECONCILING", null, null, null, null);
    }

    /**
     * 释放确认没有产生用量的运行；未知结果必须由可信核对用例确认无费用。
     *
     * @param run 运行
     * @param confirmedNoCharge 是否已确认没有费用，不能来自未授权客户端参数
     */
    @Transactional(rollbackFor = Exception.class)
    public void release(UUID run, boolean confirmedNoCharge) {
        var h = locked(run);
        if (h.state().equals("RELEASED")) return;
        if (h.state().equals("SETTLED") || !confirmedNoCharge)
            throw new BusinessException(409, "RECONCILIATION_REQUIRED", "必须确认无费用才可释放");
        delta(h.accountId(), run, "RELEASE", 0, -h.tokens(), 0, ZERO, h.amount().negate(), ZERO);
        mapper.state(run, "RELEASED", 0L, 0L, 0L, ZERO);
    }

    private Prepared prepare(UUID user) {
        if (mapper.user(user) == null) throw new BusinessException(403, "FORBIDDEN", "学生账户不可用");
        mapper.ensure(UUID.randomUUID(), user);
        var a = mapper.current(user);
        var b = mapper.benefit(user);
        if (b != null) {
            long tokenDelta = Math.max(0, b.tokens() - a.grantedTokens());
            BigDecimal amountDelta = b.amount().subtract(a.grantedAmount()).max(ZERO);
            if (tokenDelta > 0 || amountDelta.signum() > 0) {
                delta(a.id(), b.id(), "GRANT", tokenDelta, 0, 0, amountDelta, ZERO, ZERO);
                a = mapper.current(user);
            }
        }
        return new Prepared(a, b);
    }

    private QuotaHoldEntity locked(UUID run) {
        var h = mapper.hold(run);
        if (h == null) throw new BusinessException(404, "RESOURCE_NOT_FOUND", "预占不存在");
        mapper.lock(h.accountId());
        return mapper.lockHold(run);
    }

    private long availableTokens(QuotaAccountEntity a) {
        return java.math.BigInteger.valueOf(a.grantedTokens())
                .subtract(java.math.BigInteger.valueOf(a.spentTokens()))
                .subtract(java.math.BigInteger.valueOf(a.reservedTokens()))
                .max(java.math.BigInteger.ZERO)
                .longValueExact();
    }

    private BigDecimal availableAmount(QuotaAccountEntity a) {
        return a.grantedAmount().subtract(a.spentAmount()).subtract(a.reservedAmount()).max(ZERO);
    }

    private void delta(
            UUID account,
            UUID source,
            String kind,
            long gt,
            long rt,
            long st,
            BigDecimal ga,
            BigDecimal ra,
            BigDecimal sa) {
        mapper.ledger(UUID.randomUUID(), account, source, kind, gt, rt, st, ga, ra, sa);
        mapper.apply(account, gt, rt, st, ga, ra, sa);
    }

    private record Prepared(QuotaAccountEntity account, QuotaBenefitEntity benefit) {}
}
