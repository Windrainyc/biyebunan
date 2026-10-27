package cn.zhixiang.platform.chat.service;

import cn.zhixiang.platform.chat.dto.ConversationDtos;
import cn.zhixiang.platform.chat.dto.MessageResponse;
import cn.zhixiang.platform.chat.mapper.ChatAuditMapper;
import cn.zhixiang.platform.identity.mapper.IdentityMapper;
import cn.zhixiang.platform.identity.model.Actor;
import cn.zhixiang.platform.shared.dto.PageQuery;
import cn.zhixiang.platform.shared.dto.PageResult;
import cn.zhixiang.platform.shared.exception.BusinessException;
import cn.zhixiang.platform.shared.mapper.WriteMapper;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

/** 私聊读取必须与审计记录同事务；审计写失败时不返回正文。 */
@Service
public class ChatAuditService {
    private final ChatAuditMapper mapper;
    private final IdentityMapper identity;
    private final WriteMapper writes;
    private final JsonMapper json;

    /**
     * 创建审计用例。
     *
     * @param mapper 审计查询
     * @param identity 当前权限
     * @param writes 审计存储
     * @param json 引用解码
     */
    public ChatAuditService(
            ChatAuditMapper mapper, IdentityMapper identity, WriteMapper writes, JsonMapper json) {
        this.mapper = mapper;
        this.identity = identity;
        this.writes = writes;
        this.json = json;
    }

    /**
     * 读取目录并记录访问理由。
     *
     * @param q 过滤
     * @param reason 必填理由
     * @param actor 管理员
     * @return 目录
     */
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.REPEATABLE_READ)
    public PageResult<ConversationDtos.View> conversations(
            PageQuery q, String reason, Actor actor) {
        authorize(q, reason, actor);
        writes.auditReason(
                UUID.randomUUID(),
                actor.id(),
                "CHAT_CATALOG_READ",
                "conversation_catalog",
                actor.id(),
                reason);
        return new PageResult<>(
                mapper.conversations(q), q.page(), q.size(), mapper.conversationCount(q));
    }

    /**
     * 读取私人历史并记录访问理由，不把正文写入审计日志。
     *
     * @param id 会话
     * @param q 过滤
     * @param reason 理由
     * @param actor 管理员
     * @return 历史
     */
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.REPEATABLE_READ)
    public PageResult<MessageResponse> messages(UUID id, PageQuery q, String reason, Actor actor) {
        authorize(q, reason, actor);
        if (!mapper.exists(id)) throw new BusinessException(404, "RESOURCE_NOT_FOUND", "会话不存在");
        writes.auditReason(
                UUID.randomUUID(), actor.id(), "CHAT_MESSAGES_READ", "conversation", id, reason);
        return new PageResult<>(
                mapper.messages(id, q).stream()
                        .map(
                                r ->
                                        new MessageResponse(
                                                r.id(),
                                                r.conversationId(),
                                                r.runId(),
                                                r.role(),
                                                r.text(),
                                                json.readTree(r.citations()),
                                                r.createdAt()))
                        .toList(),
                q.page(),
                q.size(),
                mapper.messageCount(id, q));
    }

    private void authorize(PageQuery q, String reason, Actor actor) {
        if (!"admin".equals(actor.platform())
                || !identity.permissions(actor.id()).contains("chat:audit"))
            throw new BusinessException(403, "FORBIDDEN", "没有私聊审计权限");
        if (reason == null || reason.isBlank() || reason.length() > 1000)
            throw new BusinessException(400, "VALIDATION_ERROR", "审计原因必须为1至1000字符");
        if (q.status() != null) throw new BusinessException(400, "INVALID_FILTER", "会话和消息不支持启停筛选");
    }
}
