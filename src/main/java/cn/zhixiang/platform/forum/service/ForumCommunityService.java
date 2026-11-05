package cn.zhixiang.platform.forum.service;

import cn.zhixiang.platform.forum.dto.*;
import cn.zhixiang.platform.forum.entity.ForumFeedRow;
import cn.zhixiang.platform.forum.mapper.ForumCommunityMapper;
import cn.zhixiang.platform.identity.model.Actor;
import cn.zhixiang.platform.shared.dto.*;
import cn.zhixiang.platform.shared.exception.BusinessException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.util.*;

/** 论坛展示用例，权限来自服务端学生身份。 */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class ForumCommunityService {
    private final ForumCommunityMapper mapper;

    /**
     * 读取或初始化论坛社区数据。
     *
     * @param mapper 社区查询端口
     */
    public ForumCommunityService(ForumCommunityMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 查询真实列表。
     *
     * @param q 分页搜索
     * @param board 板块
     * @param scope all、mine、participated
     * @param mode latest、hot、unanswered
     * @param actor 服务端身份
     * @return 分页结果
     */
    public PageResult<ForumFeedResponse> feed(
            PageQuery q, UUID board, String scope, String mode, Actor actor) {
        student(actor);
        if (!Set.of("all", "mine", "participated").contains(scope)
                || !Set.of("latest", "hot", "unanswered").contains(mode))
            throw new BusinessException(400, "INVALID_FILTER", "论坛筛选条件无效");
        return new PageResult<>(
                mapper.feed(q, board, scope, mode, actor.id()).stream().map(this::view).toList(),
                q.page(),
                q.size(),
                mapper.count(q, board, scope, mode, actor.id()));
    }

    /**
     * 读取或初始化论坛社区数据。
     *
     * @param actor 学生身份
     * @return 公开社区摘要
     */
    public ForumCommunityDtos.Summary summary(Actor actor) {
        student(actor);
        return new ForumCommunityDtos.Summary(
                mapper.active().stream()
                        .map(
                                r ->
                                        new ForumActiveResponse(
                                                r.id(),
                                                r.displayName(),
                                                r.avatarIndex(),
                                                r.contributions()))
                        .toList(),
                mapper.hot().stream().map(this::view).toList(),
                mapper.keywords().stream()
                        .map(r -> new ForumKeywordResponse(r.name(), r.count()))
                        .toList());
    }

    private ForumFeedResponse view(ForumFeedRow r) {
        return new ForumFeedResponse(
                r.id(),
                r.title(),
                r.body(),
                r.boardId(),
                r.boardName(),
                r.authorId(),
                r.authorName(),
                r.avatarIndex(),
                r.state(),
                r.createdAt(),
                r.replyCount(),
                r.heat());
    }

    private void student(Actor actor) {
        if (!"student".equals(actor.platform()))
            throw new BusinessException(403, "FORBIDDEN", "当前平台不能访问学生论坛");
    }
}
