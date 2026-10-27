package cn.zhixiang.platform.chat.service;

import cn.zhixiang.platform.billing.mapper.QuotaMapper;
import cn.zhixiang.platform.billing.service.QuotaService;
import cn.zhixiang.platform.billing.service.UsageEvidenceService;
import cn.zhixiang.platform.chat.entity.RunJournalHeadEntity;
import cn.zhixiang.platform.chat.mapper.RunJournalMapper;
import cn.zhixiang.platform.chat.mapper.RunLeaseMapper;
import cn.zhixiang.platform.chat.port.ModelGateway;
import cn.zhixiang.platform.shared.exception.BusinessException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/** 运行结束事务：额度账户→运行→预占，账务、消息、流终态一起提交；禁止在此调用供应商。 */
@Service
public class RunCompletionService {
    private final UsageEvidenceService evidence;
    private final QuotaMapper accounts;
    private final QuotaService quotas;
    private final RunJournalMapper runs;
    private final RunLeaseMapper leases;
    private final MessageService messages;
    private final RunJournal journal;

    /**
     * 创建结束用例。
     *
     * @param evidence 原调用返回证据
     * @param accounts 额度锁
     * @param quotas 账务规则
     * @param runs 运行锁
     * @param leases fencing及终态
     * @param messages 不可变消息
     * @param journal 持久事件
     */
    public RunCompletionService(
            UsageEvidenceService evidence,
            QuotaMapper accounts,
            QuotaService quotas,
            RunJournalMapper runs,
            RunLeaseMapper leases,
            MessageService messages,
            RunJournal journal) {
        this.evidence = evidence;
        this.accounts = accounts;
        this.quotas = quotas;
        this.runs = runs;
        this.leases = leases;
        this.messages = messages;
        this.journal = journal;
    }

    /**
     * 原子保存实际用量和最终答复；相同完成结果重放不重复计费或发事件。
     *
     * @param run 运行
     * @param owner 领取者
     * @param generation 代次
     * @param text 完整答复
     * @param usage 真实供应商用量
     * @return 是否接受当前代次结果
     * @throws BusinessException 无预占、重复结果冲突或消息不合法
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean success(
            UUID run, UUID owner, long generation, String text, ModelGateway.Usage usage) {
        var head = lock(run);
        if (!evidence.record(run, owner, generation, usage)) return false;
        if (head.state().equals("SUCCEEDED")) {
            quotas.settle(run, usage.inputTokens(), usage.outputTokens(), usage.cachedTokens());
            messages.save(run, "ASSISTANT", text);
            return true;
        }
        if (!head.state().equals("RUNNING")) return false;
        var settled =
                quotas.settle(run, usage.inputTokens(), usage.outputTokens(), usage.cachedTokens());
        boolean empty = text == null || text.isBlank();
        UUID message = empty ? null : messages.save(run, "ASSISTANT", text);
        journal.append(
                run,
                "usage",
                Map.of(
                        "inputTokens",
                        usage.inputTokens(),
                        "outputTokens",
                        usage.outputTokens(),
                        "amount",
                        settled.actualAmount().toPlainString(),
                        "billingState",
                        "SETTLED"));
        String state = empty ? "FAILED" : "SUCCEEDED",
                error = empty ? "MODEL_EMPTY_RESPONSE" : null;
        terminalEvent(run, empty ? "failed" : "completed", state, message, error);
        leases.terminal(run, state, error, usage.providerRequestId());
        return true;
    }

    /**
     * 确定拒绝释放预占，未知结果保持冻结；旧代次结果不会覆盖当前运行。
     *
     * @param run 运行
     * @param owner 领取者
     * @param generation 代次
     * @param failure 不含敏感正文的错误
     * @return 是否接受错误结果
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean failure(UUID run, UUID owner, long generation, ModelGateway.Failure failure) {
        var head = lock(run);
        if (!leases.owner(run, owner, generation)) return false;
        if (failure.noCharge()) evidence.record(run, owner, generation, null);
        if (!head.state().equals("RUNNING")) return false;
        if (failure.noCharge()) quotas.release(run, true);
        else quotas.unknown(run);
        terminalEvent(run, "failed", "FAILED", null, failure.code());
        leases.terminal(run, "FAILED", failure.code(), null);
        return true;
    }

    /**
     * 调用前发现配置/权限不可用，安全释放；只接受原准备租约。
     *
     * @param run 运行
     * @param owner 持有者
     * @param generation 代次
     * @param code 稳定错误码
     * @return 是否结束
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean rejectBeforeCall(UUID run, UUID owner, long generation, String code) {
        var head = lock(run);
        if (!head.state().equals("RESERVED") || !leases.owner(run, owner, generation)) return false;
        quotas.release(run, true);
        terminalEvent(run, "failed", "FAILED", null, code);
        leases.terminal(run, "FAILED", code, null);
        return true;
    }

    private RunJournalHeadEntity lock(UUID run) {
        var hold = accounts.hold(run);
        if (hold == null) throw new BusinessException(404, "RESOURCE_NOT_FOUND", "运行预占不存在");
        accounts.lock(hold.accountId());
        var head = runs.lock(run);
        if (head == null) throw new BusinessException(404, "RESOURCE_NOT_FOUND", "运行不存在");
        return head;
    }

    private void terminalEvent(UUID run, String type, String state, UUID message, String error) {
        var data = new LinkedHashMap<String, Object>();
        data.put("state", state);
        data.put("messageId", message);
        data.put("errorCode", error);
        journal.append(run, type, data);
    }
}
