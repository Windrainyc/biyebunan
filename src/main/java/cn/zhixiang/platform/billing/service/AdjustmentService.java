package cn.zhixiang.platform.billing.service;

import cn.zhixiang.platform.billing.dto.AdjustmentDtos;
import cn.zhixiang.platform.billing.mapper.AdjustmentMapper;
import cn.zhixiang.platform.billing.mapper.QuotaMapper;
import cn.zhixiang.platform.identity.model.Actor;
import cn.zhixiang.platform.shared.exception.BusinessException;
import cn.zhixiang.platform.shared.mapper.WriteMapper;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/** 人工财务调整，锁账户后锁预占，与结算一致；不修改原始供应商用量。 */
@Service
public class AdjustmentService {
    private final AdjustmentMapper mapper;
    private final QuotaMapper quotas;
    private final WriteMapper writes;

    /**
     * 创建用例。
     *
     * @param mapper 调整存储
     * @param quotas 原子账本与账户
     * @param writes 审计事件
     */
    public AdjustmentService(AdjustmentMapper mapper, QuotaMapper quotas, WriteMapper writes) {
        this.mapper = mapper;
        this.quotas = quotas;
        this.writes = writes;
    }

    /**
     * 追加调整；信用调整不能超过该运行累计已扣金额，未知账务拒绝人工绕过。
     *
     * @param w 已校验输入
     * @param actor 管理员，入口另校验billing:adjust
     * @return 调整单
     * @throws BusinessException 非管理员、未结算、超额退还或金额非法
     */
    @Transactional(rollbackFor = Exception.class)
    public AdjustmentDtos.View create(AdjustmentDtos.Write w, Actor actor) {
        if (!"admin".equals(actor.platform()))
            throw new BusinessException(403, "FORBIDDEN", "仅管理员可调整账务");
        BigDecimal amount = new BigDecimal(w.amount());
        if (amount.signum() <= 0)
            throw new BusinessException(422, "ADJUSTMENT_INVALID", "调整金额必须大于0");
        var initial = quotas.hold(w.runId());
        if (initial == null) throw new BusinessException(404, "RESOURCE_NOT_FOUND", "运行账务不存在");
        quotas.lock(initial.accountId());
        var h = quotas.lockHold(w.runId());
        if (!"SETTLED".equals(h.state()) && !quotas.corrected(w.runId()))
            throw new BusinessException(409, "RECONCILIATION_REQUIRED", "仅已结算运行可调整，未知用量须先核对");
        BigDecimal delta =
                w.direction() == AdjustmentDtos.Direction.DEBIT ? amount : amount.negate();
        if ((h.actualAmount() == null ? BigDecimal.ZERO : h.actualAmount())
                        .add(quotas.netAdjustments(w.runId()))
                        .add(delta)
                        .signum()
                < 0)
            throw new BusinessException(422, "ADJUSTMENT_EXCEEDS_CHARGE", "累计退还不能超过该运行已扣金额");
        UUID id = UUID.randomUUID();
        mapper.insert(id, h.accountId(), w, actor.id());
        quotas.ledger(
                UUID.randomUUID(),
                h.accountId(),
                id,
                "ADJUSTMENT",
                0,
                0,
                0,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                delta);
        quotas.apply(h.accountId(), 0, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO, delta);
        writes.auditReason(
                UUID.randomUUID(), actor.id(), "ADJUSTMENT", "billing_adjustment", id, w.reason());
        writes.event(UUID.randomUUID(), id, "billing.ADJUSTMENT", "{\"id\":\"" + id + "\"}");
        return mapper.get(id);
    }
}
