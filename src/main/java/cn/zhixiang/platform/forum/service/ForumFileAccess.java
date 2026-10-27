package cn.zhixiang.platform.forum.service;

import cn.zhixiang.platform.forum.dto.BoardDtos;
import cn.zhixiang.platform.forum.entity.PostEntity;
import cn.zhixiang.platform.forum.mapper.BoardMapper;
import cn.zhixiang.platform.forum.mapper.PostMapper;
import cn.zhixiang.platform.identity.model.Actor;
import cn.zhixiang.platform.shared.exception.BusinessException;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.*;

/** 论坛文件权限端口；不依赖资产用例，避免帖子与文件互相依赖。 */
@Service
public class ForumFileAccess {
    private final PostMapper posts;
    private final BoardMapper boards;

    /**
     * 注入归属端口。
     *
     * @param posts 帖子
     * @param boards 板块
     */
    public ForumFileAccess(PostMapper posts, BoardMapper boards) {
        this.posts = posts;
        this.boards = boards;
    }

    /**
     * 上传/完成/删除前在调用方事务中锁板块→帖子，先验权再访问资产。
     *
     * @param id 帖子
     * @param actor 当前身份
     * @throws BusinessException 不可见、非作者或只读状态
     */
    public void write(UUID id, Actor actor) {
        var initial = posts.get(id);
        live(initial);
        var b = boards.lock(initial.boardId());
        var p = posts.lock(id);
        live(p);
        if (!initial.boardId().equals(p.boardId()))
            throw new BusinessException(412, "VERSION_CONFLICT", "帖子板块已变化，请重试");
        if (actor.platform().equals("admin")) {
            permission("post_admin_posts_id_moderation");
        } else {
            if (!actor.platform().equals("student") || !actor.id().equals(p.authorId())) missing();
            permission("put_student_posts_id");
        }
        if (b.status() != BoardDtos.Status.ENABLED
                || !Set.of("PENDING", "PUBLISHED").contains(p.state()))
            throw new BusinessException(422, "POST_STATE_INVALID", "当前帖子不接受文件写入");
    }

    /**
     * 私有文件读取授权；未关联文件只允许作者查看处理状态或下载预览。
     *
     * @param id 帖子
     * @param asset 资产
     * @param actor 当前身份
     * @throws BusinessException 不可见时404
     */
    public void read(UUID id, UUID asset, Actor actor) {
        var p = posts.get(id);
        live(p);
        if (actor.platform().equals("admin")) {
            permission("get_admin_posts_id");
            return;
        }
        if (!actor.platform().equals("student")) missing();
        permission("get_student_posts_id");
        var b = boards.get(p.boardId());
        if (b == null || b.status() != BoardDtos.Status.ENABLED || p.state().equals("HIDDEN"))
            missing();
        if (p.authorId().equals(actor.id())) return;
        if (!Set.of("PUBLISHED", "LOCKED").contains(p.state()) || !posts.hasAsset(id, asset))
            missing();
    }

    private void permission(String value) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null
                || auth.getAuthorities().stream().noneMatch(a -> a.getAuthority().equals(value)))
            throw new BusinessException(403, "FORBIDDEN", "缺少论坛文件业务权限");
    }

    private void live(PostEntity p) {
        if (p == null || p.deletedAt() != null) missing();
    }

    private void missing() {
        throw new BusinessException(404, "RESOURCE_NOT_FOUND", "帖子或文件不存在");
    }
}
