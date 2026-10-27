package cn.zhixiang.platform.operations.service;

import cn.zhixiang.platform.identity.mapper.IdentityMapper;
import cn.zhixiang.platform.identity.security.TokenDigester;
import cn.zhixiang.platform.shared.exception.BusinessException;
import cn.zhixiang.platform.shared.mapper.WriteMapper;

import org.springframework.amqp.core.Message;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** 死信恢复的数据库事务；沿用真实管理员会话与任务恢复权限，不信任命令行声明的身份。 */
@Service
public class DeadLetterReplayService {
    private final IdentityMapper identities;
    private final TokenDigester digester;
    private final ActivityInbox inbox;
    private final WriteMapper writes;

    /**
     * 创建恢复用例。
     *
     * @param identities 真实会话与权限
     * @param digester 令牌摘要
     * @param inbox 原统计消费用例
     * @param writes 事务审计
     */
    public DeadLetterReplayService(
            IdentityMapper identities,
            TokenDigester digester,
            ActivityInbox inbox,
            WriteMapper writes) {
        this.identities = identities;
        this.digester = digester;
        this.inbox = inbox;
        this.writes = writes;
    }

    /**
     * 校验会话后重做原消费，数据库提交后调用者才能ACK。重复恢复不重复累计统计。
     *
     * @param message 原始死信字节，禁止重新编码
     * @param token 管理员当前访问令牌，仅内存使用
     * @param reason 受控恢复原因，1至200字，不得含秘密
     * @return 是否首次消费；false表示已提交的精确重复
     * @throws BusinessException 无权限、消息无效或同键不同正文
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean replay(Message message, String token, String reason) {
        if (token == null || token.isBlank() || token.length() > 1024)
            throw new BusinessException(401, "UNAUTHENTICATED", "需要有效管理员会话");
        var actor = identities.authenticate(digester.digest("access", token));
        if (actor == null) throw new BusinessException(401, "UNAUTHENTICATED", "会话不可用");
        if (!actor.platform().equals("admin")
                || !identities.permissions(actor.id()).contains("post_admin_jobs_id_retry"))
            throw new BusinessException(403, "FORBIDDEN", "缺少任务恢复权限");
        if (reason == null || reason.isBlank() || reason.length() > 200)
            throw new BusinessException(400, "VALIDATION_ERROR", "需要1至200字恢复原因");
        boolean first = inbox.consume(message);
        writes.auditReason(
                UUID.randomUUID(),
                actor.id(),
                "DEAD_LETTER_REPLAY",
                "activity_event",
                UUID.fromString(message.getMessageProperties().getMessageId()),
                reason);
        return first;
    }
}
