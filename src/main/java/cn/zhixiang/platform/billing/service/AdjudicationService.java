package cn.zhixiang.platform.billing.service;

import cn.zhixiang.platform.billing.dto.AdjudicationDtos;
import cn.zhixiang.platform.billing.mapper.*;
import cn.zhixiang.platform.chat.mapper.RunJournalMapper;
import cn.zhixiang.platform.identity.mapper.IdentityMapper;
import cn.zhixiang.platform.identity.model.Actor;
import cn.zhixiang.platform.shared.dto.PageResult;
import cn.zhixiang.platform.shared.exception.BusinessException;
import cn.zhixiang.platform.shared.mapper.WriteMapper;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.util.*;

/** 未知账务的人工双人裁决；不写原调用证据表，批准与账本变动原子提交。 */
@Service
public class AdjudicationService {
    private final AdjudicationMapper mapper;
    private final QuotaMapper accounts;
    private final QuotaService quotas;
    private final RunJournalMapper runs;
    private final UsageEvidenceMapper evidence;
    private final IdentityMapper identity;
    private final WriteMapper writes;

    /**
     * 组合裁决依赖，不调用外部模型或付款渠道。
     *
     * @param mapper 裁决存储
     * @param accounts 账务账户锁
     * @param quotas 结算与释放规则
     * @param runs 运行锁
     * @param evidence 原调用证据
     * @param identity 当前权限
     * @param writes 审计及outbox
     */
    public AdjudicationService(
            AdjudicationMapper mapper,
            QuotaMapper accounts,
            QuotaService quotas,
            RunJournalMapper runs,
            UsageEvidenceMapper evidence,
            IdentityMapper identity,
            WriteMapper writes) {
        this.mapper = mapper;
        this.accounts = accounts;
        this.quotas = quotas;
        this.runs = runs;
        this.evidence = evidence;
        this.identity = identity;
        this.writes = writes;
    }

    /**
     * 创建待复核声明；相同运行只允许一个待审单，不改变冻结余额。
     *
     * @param run 运行ID
     * @param w 已验证证据摘要和声明
     * @param actor 当前管理员
     * @return 新单据
     * @throws BusinessException 无权限、无需核对、原证据已到达或待审重复
     */
    @Transactional(rollbackFor = Exception.class)
    public AdjudicationDtos.View create(UUID run, AdjudicationDtos.Create w, Actor actor) {
        authorize(actor);
        lock(run);
        requireUnresolved(run);
        boolean usage = "USAGE".equals(w.kind()), noCharge = "NO_CHARGE".equals(w.kind());
        if ((!usage && !noCharge)
                || (usage
                        && (w.inputTokens() == null
                                || w.outputTokens() == null
                                || w.cachedTokens() == null
                                || w.inputTokens() < 0
                                || w.outputTokens() < 0
                                || w.cachedTokens() < 0
                                || w.cachedTokens() > w.inputTokens()
                                || w.inputTokens() > 1000000000L
                                || w.outputTokens() > 1000000000L))
                || (noCharge
                        && (w.inputTokens() != null
                                || w.outputTokens() != null
                                || w.cachedTokens() != null)))
            throw new BusinessException(422, "ADJUDICATION_USAGE_INVALID", "用量字段与声明类型不一致");
        if (mapper.pending(run))
            throw new BusinessException(409, "ADJUDICATION_PENDING", "此运行已有待复核单据");
        UUID id = UUID.randomUUID();
        mapper.insert(id, run, w, actor.id());
        writes.auditReason(
                UUID.randomUUID(),
                actor.id(),
                "ADJUDICATION_SUBMITTED",
                "usage_adjudication",
                id,
                w.reason());
        return mapper.get(id);
    }

    /**
     * 获取可审计单据。
     *
     * @param id 单据标识
     * @param actor 管理员
     * @return 单据
     */
    public AdjudicationDtos.View get(UUID id, Actor actor) {
        authorize(actor);
        return existing(id);
    }

    /**
     * 查询分页，排序固定为提交时间倒序。
     *
     * @param run 可空运行过滤
     * @param state 可空状态过滤
     * @param page 从1开始
     * @param size 1至100
     * @param actor 管理员
     * @return 一致快照的页及总数
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResult<AdjudicationDtos.View> list(
            UUID run, String state, int page, int size, Actor actor) {
        authorize(actor);
        if (page < 1
                || size < 1
                || size > 100
                || (long) (page - 1) * size > Integer.MAX_VALUE
                || state != null && !Set.of("PENDING", "APPROVED", "REJECTED").contains(state))
            throw new BusinessException(400, "INVALID_FILTER", "分页或状态不合法");
        return new PageResult<>(
                mapper.list(run, state, size, (page - 1) * size),
                page,
                size,
                mapper.count(run, state));
    }

    /**
     * 第二位管理员复核；账户→运行→裁决单→预占的锁顺序与核对一致。
     *
     * @param id 裁决单
     * @param version If-Match版本
     * @param decision 审核决定及原因
     * @param actor 独立复核管理员
     * @return 终态单据
     * @throws BusinessException 自审、版本冲突、已终结或原始证据已到达
     */
    @Transactional(rollbackFor = Exception.class)
    public AdjudicationDtos.View decide(
            UUID id, long version, AdjudicationDtos.Decision decision, Actor actor) {
        authorize(actor);
        var initial = existing(id);
        lock(initial.runId());
        var row = mapper.lock(id);
        if (row.version() != version)
            throw new BusinessException(412, "VERSION_CONFLICT", "裁决单已被更新");
        if (!"PENDING".equals(row.state()))
            throw new BusinessException(409, "ADJUDICATION_CLOSED", "裁决已结束");
        if (row.createdBy().equals(actor.id()))
            throw new BusinessException(403, "ADJUDICATION_SELF_REVIEW", "提交人与复核人必须不同");
        if (!Set.of("APPROVE", "REJECT").contains(decision.decision()))
            throw new BusinessException(422, "ADJUDICATION_DECISION_INVALID", "审核决定无效");
        boolean approved = "APPROVE".equals(decision.decision());
        if (approved) {
            requireUnresolved(row.runId());
            if ("USAGE".equals(row.kind()))
                quotas.settle(
                        row.runId(), row.inputTokens(), row.outputTokens(), row.cachedTokens());
            else quotas.release(row.runId(), true);
        }
        String state = approved ? "APPROVED" : "REJECTED";
        if (mapper.decide(id, version, state, decision.reason(), actor.id()) != 1)
            throw new BusinessException(412, "VERSION_CONFLICT", "裁决单已被更新");
        writes.auditReason(
                UUID.randomUUID(),
                actor.id(),
                "ADJUDICATION_" + state,
                "usage_adjudication",
                id,
                decision.reason());
        writes.event(
                UUID.randomUUID(), id, "billing.ADJUDICATION_" + state, "{\"id\":\"" + id + "\"}");
        return mapper.get(id);
    }

    private void authorize(Actor actor) {
        if (!"admin".equals(actor.platform())
                || !identity.permissions(actor.id()).contains("billing:adjudicate"))
            throw new BusinessException(403, "FORBIDDEN", "需要用量裁决权限");
    }

    private AdjudicationDtos.View existing(UUID id) {
        var row = mapper.get(id);
        if (row == null) throw new BusinessException(404, "RESOURCE_NOT_FOUND", "裁决单不存在");
        return row;
    }

    private void lock(UUID run) {
        var hold = accounts.hold(run);
        if (hold == null) throw new BusinessException(404, "RESOURCE_NOT_FOUND", "运行账务不存在");
        accounts.lock(hold.accountId());
        runs.lock(run);
    }

    private void requireUnresolved(UUID run) {
        if (!"RECONCILING".equals(accounts.hold(run).state()))
            throw new BusinessException(409, "RECONCILIATION_NOT_REQUIRED", "当前账务不处于待核对状态");
        if (evidence.get(run) != null)
            throw new BusinessException(409, "ORIGINAL_EVIDENCE_AVAILABLE", "原调用证据已到达，请使用可信证据核对流程");
    }
}
