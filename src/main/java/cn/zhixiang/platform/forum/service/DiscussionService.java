package cn.zhixiang.platform.forum.service;

import cn.zhixiang.platform.forum.dto.CommentDtos;
import cn.zhixiang.platform.forum.dto.PostDtos;
import cn.zhixiang.platform.forum.dto.ReportDtos;
import cn.zhixiang.platform.forum.entity.DiscussionCommentRowEntity;
import cn.zhixiang.platform.forum.entity.DiscussionReportRowEntity;
import cn.zhixiang.platform.forum.entity.PostEntity;
import cn.zhixiang.platform.forum.mapper.BoardMapper;
import cn.zhixiang.platform.forum.mapper.DiscussionMapper;
import cn.zhixiang.platform.forum.mapper.PostMapper;
import cn.zhixiang.platform.identity.model.Actor;
import cn.zhixiang.platform.shared.dto.PageQuery;
import cn.zhixiang.platform.shared.dto.PageResult;
import cn.zhixiang.platform.shared.exception.BusinessException;
import cn.zhixiang.platform.shared.mapper.WriteMapper;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.util.*;

/** 评论和举报用例；账户→板块→帖子锁序与帖子编辑一致，治理不会与评论写入穿透。 */
@Service
public class DiscussionService {
    private final DiscussionMapper mapper;
    private final PostMapper posts;
    private final BoardMapper boards;
    private final PostService access;
    private final WriteMapper writes;
    private final cn.zhixiang.platform.notification.service.NotificationService notifications;

    /**
     * 创建用例。
     *
     * @param mapper 评论举报
     * @param posts 帖子锁
     * @param boards 板块锁
     * @param access 可见性
     * @param notifications 管理员工作通知
     * @param writes 审计
     */
    public DiscussionService(
            DiscussionMapper mapper,
            PostMapper posts,
            BoardMapper boards,
            PostService access,
            WriteMapper writes,
            cn.zhixiang.platform.notification.service.NotificationService notifications) {
        this.mapper = mapper;
        this.posts = posts;
        this.boards = boards;
        this.access = access;
        this.writes = writes;
        this.notifications = notifications;
    }

    /**
     * 可见帖子评论分页，隐藏父评论的回复也不可见。
     *
     * @param id 帖子
     * @param q 分页
     * @param state PUBLISHED/HIDDEN
     * @param actor 身份
     * @return 分页
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResult<CommentDtos.View> comments(UUID id, PageQuery q, String state, Actor actor) {
        access.get(id, actor);
        filter(state, Set.of("PUBLISHED", "HIDDEN"));
        boolean admin = actor.platform().equals("admin");
        return new PageResult<>(
                mapper.comments(id, q, state, admin).stream().map(this::commentView).toList(),
                q.page(),
                q.size(),
                mapper.countComments(id, q, state, admin));
    }

    /**
     * 创建主评论或一层回复；锁定帖子拒绝继续评论。
     *
     * @param id 帖子
     * @param w 正文
     * @param actor 学生
     * @return 新评论
     */
    @Transactional(rollbackFor = Exception.class)
    public CommentDtos.View create(UUID id, CommentDtos.Write w, Actor actor) {
        student(actor);
        var post = lockPost(id);
        access.get(id, actor);
        if (!post.state().equals("PUBLISHED")) invalid();
        if (w.parentId() != null) {
            var p = mapper.comment(w.parentId());
            if (p == null
                    || !p.postId().equals(id)
                    || p.deletedAt() != null
                    || !p.state().equals("PUBLISHED")
                    || p.parentId() != null)
                throw new BusinessException(422, "COMMENT_PARENT_INVALID", "只能回复当前帖子下可见的主评论");
        }
        if (mapper.recentComments(actor.id()) >= 20)
            throw new BusinessException(429, "FORUM_RATE_LIMITED", "评论过于频繁，请稍后重试");
        UUID comment = UUID.randomUUID();
        mapper.insertComment(comment, id, actor.id(), w);
        audit(comment, actor, "COMMENT_CREATED", null);
        return commentView(mapper.comment(comment));
    }

    /**
     * 软删除本人评论；非本人统一404。
     *
     * @param id 评论
     * @param version CAS
     * @param actor 作者
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(UUID id, long version, Actor actor) {
        student(actor);
        var initial = mapper.comment(id);
        live(initial);
        lockPost(initial.postId());
        var row = mapper.comment(id);
        live(row);
        if (!row.authorId().equals(actor.id())) missing();
        version(row.version(), version);
        mapper.commentState(id, row.state(), true);
        audit(id, actor, "COMMENT_DELETED", null);
    }

    /**
     * 管理员隐藏或恢复评论，不能借动作修改正文。
     *
     * @param id 评论
     * @param version CAS
     * @param w 审核理由
     * @param actor 管理员
     * @return 新版本
     */
    @Transactional(rollbackFor = Exception.class)
    public CommentDtos.View moderate(UUID id, long version, PostDtos.Moderation w, Actor actor) {
        admin(actor);
        var initial = mapper.comment(id);
        live(initial);
        lockPost(initial.postId());
        var row = mapper.comment(id);
        live(row);
        version(row.version(), version);
        if (!Set.of("APPROVE", "HIDE").contains(w.action())) invalid();
        mapper.commentState(id, w.action().equals("APPROVE") ? "PUBLISHED" : "HIDDEN", false);
        audit(id, actor, "COMMENT_" + w.action(), w.reason());
        return commentView(mapper.comment(id));
    }

    /**
     * 每人每帖只保留一条举报；换幂等键仍不会重复创建。
     *
     * @param id 帖子
     * @param w 理由
     * @param actor 举报人
     * @return 原举报或新举报
     */
    @Transactional(rollbackFor = Exception.class)
    public ReportDtos.View report(UUID id, ReportDtos.Reason w, Actor actor) {
        student(actor);
        lockPost(id);
        access.get(id, actor);
        var old = mapper.existing(id, actor.id());
        if (old != null) {
            if (!old.reason().equals(w.reason()))
                throw new BusinessException(409, "REPORT_ALREADY_EXISTS", "已经举报过该帖子，不能更改原举报");
            return reportView(old);
        }
        if (mapper.recentReports(actor.id()) >= 20)
            throw new BusinessException(429, "FORUM_RATE_LIMITED", "举报过于频繁，请稍后重试");
        UUID report = UUID.randomUUID();
        mapper.report(report, id, actor.id(), w.reason());
        audit(report, actor, "REPORTED", w.reason());
        return reportView(mapper.reportRow(report));
    }

    /**
     * 管理员举报目录；学生和中介没有该读取能力。
     *
     * @param q 分页
     * @param state 处理状态
     * @param actor 管理员
     * @return 分页
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResult<ReportDtos.View> reports(PageQuery q, String state, Actor actor) {
        admin(actor);
        filter(state, Set.of("OPEN", "RESOLVED", "DISMISSED"));
        return new PageResult<>(
                mapper.reports(q, state).stream().map(this::reportView).toList(),
                q.page(),
                q.size(),
                mapper.countReports(q, state));
    }

    /**
     * 一次性处理举报；结论不隐式改变帖子状态，治理使用独立审核接口。
     *
     * @param id 举报
     * @param version CAS
     * @param w 结论依据
     * @param actor 管理员
     * @return 处理后举报
     */
    @Transactional(rollbackFor = Exception.class)
    public ReportDtos.View decide(UUID id, long version, ReportDtos.Decision w, Actor actor) {
        admin(actor);
        var row = mapper.lockReport(id);
        if (row == null) missing();
        version(row.version(), version);
        if (!row.state().equals("OPEN")) invalid();
        mapper.decision(id, w.decision());
        audit(id, actor, "REPORT_" + w.decision(), w.reason());
        return reportView(mapper.reportRow(id));
    }

    private PostEntity lockPost(UUID id) {
        var old = posts.get(id);
        if (old == null || old.deletedAt() != null) missing();
        boards.lock(old.boardId());
        var row = posts.lock(id);
        if (row == null || row.deletedAt() != null) missing();
        if (!row.boardId().equals(old.boardId()))
            throw new BusinessException(412, "VERSION_CONFLICT", "帖子板块已改变，请重试");
        return row;
    }

    private void filter(String value, Set<String> allowed) {
        if (value != null && !allowed.contains(value))
            throw new BusinessException(400, "INVALID_FILTER", "状态筛选不合法");
    }

    private void student(Actor a) {
        if (!a.platform().equals("student")) forbidden();
    }

    private void admin(Actor a) {
        if (!a.platform().equals("admin")) forbidden();
    }

    private void forbidden() {
        throw new BusinessException(403, "FORBIDDEN", "平台权限不足");
    }

    private void missing() {
        throw new BusinessException(404, "RESOURCE_NOT_FOUND", "目标不存在");
    }

    private void invalid() {
        throw new BusinessException(422, "FORUM_STATE_INVALID", "当前状态或动作不允许此操作");
    }

    private void live(DiscussionCommentRowEntity r) {
        if (r == null || r.deletedAt() != null) missing();
    }

    private void version(long actual, long expected) {
        if (actual != expected) throw new BusinessException(412, "VERSION_CONFLICT", "资源已修改");
    }

    private void audit(UUID id, Actor actor, String action, String reason) {
        writes.auditReason(UUID.randomUUID(), actor.id(), action, "forum_discussion", id, reason);
        UUID event = UUID.randomUUID();
        writes.event(event, id, "forum_discussion." + action, "{\"id\":\"" + id + "\"}");
        if (action.equals("REPORTED"))
            notifications.enqueueAdministrators(
                    event, "get_admin_reports", "学生举报待处理", "收到新的论坛举报，请核查并处理。");
    }

    private CommentDtos.View commentView(DiscussionCommentRowEntity r) {
        return new CommentDtos.View(
                r.id(),
                r.version(),
                r.createdAt(),
                r.updatedAt(),
                r.body(),
                r.parentId(),
                r.postId(),
                r.authorId(),
                r.state());
    }

    private ReportDtos.View reportView(DiscussionReportRowEntity r) {
        return new ReportDtos.View(
                r.id(),
                r.version(),
                r.createdAt(),
                r.updatedAt(),
                r.postId(),
                r.reporterId(),
                r.reason(),
                r.state());
    }
}
