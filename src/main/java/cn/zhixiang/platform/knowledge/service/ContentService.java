package cn.zhixiang.platform.knowledge.service;

import cn.zhixiang.platform.asset.mapper.AssetMapper;
import cn.zhixiang.platform.identity.model.Actor;
import cn.zhixiang.platform.knowledge.dto.ContentDtos;
import cn.zhixiang.platform.knowledge.dto.TagDtos;
import cn.zhixiang.platform.knowledge.entity.ContentEntity;
import cn.zhixiang.platform.knowledge.mapper.CategoryMapper;
import cn.zhixiang.platform.knowledge.mapper.ContentMapper;
import cn.zhixiang.platform.knowledge.mapper.IndexMapper;
import cn.zhixiang.platform.knowledge.mapper.TagMapper;
import cn.zhixiang.platform.project.mapper.ProjectMapper;
import cn.zhixiang.platform.shared.dto.PageResult;
import cn.zhixiang.platform.shared.exception.BusinessException;
import cn.zhixiang.platform.shared.mapper.WriteMapper;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/** 知识内容用例；已发布内容须先撤回才可修改，避免草稿泄漏给学生。 */
@Service
public class ContentService {
    private final IndexMapper indexes;
    private final ContentMapper mapper;
    private final CategoryMapper categories;
    private final TagMapper tags;
    private final AssetMapper assets;
    private final ProjectMapper projects;
    private final WriteMapper writes;

    /**
     * 创建用例。
     *
     * @param indexes 索引入队
     * @param mapper 内容端口
     * @param categories 分类端口
     * @param tags 标签端口
     * @param assets 资产端口
     * @param projects 项目端口
     * @param writes 审计事件
     */
    public ContentService(
            IndexMapper indexes,
            ContentMapper mapper,
            CategoryMapper categories,
            TagMapper tags,
            AssetMapper assets,
            ProjectMapper projects,
            WriteMapper writes) {
        this.indexes = indexes;
        this.mapper = mapper;
        this.categories = categories;
        this.tags = tags;
        this.assets = assets;
        this.projects = projects;
        this.writes = writes;
    }

    /**
     * 授权分页。
     *
     * @param q 查询
     * @param actor 身份
     * @return 分页
     */
    @Transactional(
            readOnly = true,
            isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public PageResult<ContentDtos.View> list(ContentDtos.Query q, Actor actor) {
        readRole(actor);
        return new PageResult<>(
                mapper.list(q, actor.platform().equals("admin"), actor.id()).stream()
                        .map(this::view)
                        .toList(),
                q.page(),
                q.size(),
                mapper.count(q, actor.platform().equals("admin"), actor.id()));
    }

    /**
     * 授权详情。
     *
     * @param id 内容
     * @param actor 身份
     * @return 内容
     */
    @Transactional(
            readOnly = true,
            isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public ContentDtos.View get(UUID id, Actor actor) {
        readRole(actor);
        var r = mapper.get(id, actor.platform().equals("admin"), actor.id());
        if (r == null) missing();
        return view(r);
    }

    /**
     * 管理员分页查看处理原因；禁止学生和中介访问。
     *
     * @param id 内容标识
     * @param page 页码
     * @param size 页大小
     * @param actor 服务端身份
     * @return 稳定分页历史
     */
    @Transactional(
            readOnly = true,
            isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public PageResult<cn.zhixiang.platform.knowledge.dto.ContentEventResponse> history(
            UUID id, int page, int size, Actor actor) {
        if (!"admin".equals(actor.platform()))
            throw new BusinessException(403, "FORBIDDEN", "无权查看处理历史");
        get(id, actor);
        var q = new cn.zhixiang.platform.shared.dto.PageQuery(page, size, null, null, null);
        return new PageResult<>(
                mapper.history(id, q.size(), q.offset()).stream()
                        .map(
                                e ->
                                        new cn.zhixiang.platform.knowledge.dto.ContentEventResponse(
                                                e.id(),
                                                e.action(),
                                                e.reason(),
                                                e.actorName(),
                                                e.createdAt()))
                        .toList(),
                page,
                size,
                mapper.historyCount(id));
    }

    /**
     * 新建草稿。
     *
     * @param w 输入
     * @param actor 管理员
     * @return 草稿
     */
    @Transactional(rollbackFor = Exception.class)
    public ContentDtos.View create(ContentDtos.Write w, Actor actor) {
        admin(actor);
        UUID id = UUID.randomUUID();
        validate(id, w, actor, false);
        mapper.insert(id, w, actor.id());
        replaceTags(id, w.tagIds());
        event(id, actor.id(), "CREATED", "新建草稿");
        return get(id, actor);
    }

    /**
     * 编辑草稿/审核中/已撤回内容，编辑使审核失效。
     *
     * @param id 内容
     * @param version 版本
     * @param w 输入
     * @param actor 管理员
     * @return 草稿
     */
    @Transactional(rollbackFor = Exception.class)
    public ContentDtos.View update(UUID id, long version, ContentDtos.Write w, Actor actor) {
        admin(actor);
        categories.lockTree();
        var r = checked(id, version);
        if (r.state().equals("PUBLISHED"))
            throw new BusinessException(422, "CONTENT_PUBLISHED", "先撤回已发布内容再修改");
        validate(id, w, actor, false);
        mapper.update(id, w, actor.id());
        replaceTags(id, w.tagIds());
        event(id, actor.id(), "UPDATED", "编辑草稿");
        return get(id, actor);
    }

    /**
     * 软删除非发布内容。
     *
     * @param id 内容
     * @param version 版本
     * @param actor 管理员
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(UUID id, long version, Actor actor) {
        admin(actor);
        categories.lockTree();
        var r = checked(id, version);
        if (r.state().equals("PUBLISHED"))
            throw new BusinessException(422, "CONTENT_PUBLISHED", "先撤回已发布内容");
        mapper.delete(id, actor.id());
        event(id, actor.id(), "DELETED", "业务删除，保留快照");
    }

    /**
     * 审核提交、发布或撤回；发布快照与事件同事务。
     *
     * @param id 内容
     * @param version 版本
     * @param action REVIEW/PUBLISH/WITHDRAW
     * @param reason 原因
     * @param actor 管理员
     * @return 内容
     */
    @Transactional(rollbackFor = Exception.class)
    public ContentDtos.View transition(
            UUID id, long version, String action, String reason, Actor actor) {
        admin(actor);
        categories.lockTree();
        var r = checked(id, version);
        if (action.equals("REVIEW")) {
            if (!Set.of("DRAFT", "WITHDRAWN").contains(r.state())) invalid();
            validate(id, input(r), actor, true);
            mapper.state(id, "REVIEW", actor.id());
        } else if (action.equals("PUBLISH")) {
            if (!r.state().equals("REVIEW")) invalid();
            validate(id, input(r), actor, true);
            UUID snapshot = UUID.randomUUID();
            mapper.snapshot(snapshot, id, actor.id());
            mapper.snapshotTags(snapshot, id);
            mapper.publish(id, snapshot, actor.id());
            indexes.enqueue(UUID.randomUUID(), id);
        } else if (action.equals("WITHDRAW")) {
            if (!Set.of("REVIEW", "PUBLISHED").contains(r.state())) invalid();
            mapper.state(id, "WITHDRAWN", actor.id());
        } else invalid();
        event(id, actor.id(), action, reason);
        return get(id, actor);
    }

    private void validate(UUID id, ContentDtos.Write w, Actor actor, boolean complete) {
        categories.lockTree();
        var category = categories.get(w.categoryId());
        if (category == null || !category.status().equals("ENABLED"))
            throw new BusinessException(422, "CATEGORY_DISABLED", "分类未启用");
        if (new HashSet<>(w.tagIds()).size() != w.tagIds().size())
            throw new BusinessException(422, "DUPLICATE_TAG", "标签不能重复");
        for (UUID tag : w.tagIds().stream().sorted().toList()) {
            var t = tags.lock(tag);
            if (t == null || t.status() != TagDtos.Status.ENABLED)
                throw new BusinessException(422, "TAG_DISABLED", "标签未启用");
        }
        if (w.audience().equals("PROJECT")) {
            if (w.projectId() == null || projects.get(w.projectId(), actor) == null)
                throw new BusinessException(422, "PROJECT_REQUIRED", "项目范围须关联有效项目");
        } else if (w.projectId() != null)
            throw new BusinessException(422, "PROJECT_UNEXPECTED", "非项目范围不能关联项目");
        if (w.type().equals("MARKDOWN")) {
            if (w.assetId() != null)
                throw new BusinessException(422, "CONTENT_FORMAT_INVALID", "Markdown内容不使用附件");
            if (complete && (w.markdown() == null || w.markdown().isBlank()))
                throw new BusinessException(422, "CONTENT_EMPTY", "正文不能为空");
        } else {
            if (w.markdown() != null && !w.markdown().isBlank())
                throw new BusinessException(422, "CONTENT_FORMAT_INVALID", "视频文档不得混用Markdown正文");
            if (complete && w.assetId() == null)
                throw new BusinessException(422, "ASSET_REQUIRED", "发布须上传附件");
        }
        // 固定UUID顺序锁定正文及封面，避免多素材交叉引用发生死锁。
        var selected = new TreeSet<UUID>();
        if (w.assetId() != null) selected.add(w.assetId());
        if (w.coverAssetId() != null) selected.add(w.coverAssetId());
        for (UUID assetId : selected) {
            var a = assets.lock(assetId);
            if (a == null
                    || a.deletedAt() != null
                    || !(id.equals(a.contentId()) || a.ownerType().equals("MATERIAL"))
                    || !a.state().equals("READY"))
                throw new BusinessException(422, "CONTENT_ASSET_INVALID", "文件须为扫描通过的素材或本内容附件");
            if (a.ownerType().equals("MATERIAL")) {
                var authentication =
                        org.springframework.security.core.context.SecurityContextHolder.getContext()
                                .getAuthentication();
                if (authentication == null
                        || authentication.getAuthorities().stream()
                                .noneMatch(
                                        authority ->
                                                authority.getAuthority().equals("materials:read")))
                    throw new BusinessException(403, "FORBIDDEN", "缺少素材库读取权限");
            }
            if (assetId.equals(w.coverAssetId())
                    && !a.mimeType().matches("image/(png|jpeg|gif|webp|avif)"))
                throw new BusinessException(
                        422, "COVER_FORMAT_INVALID", "封面请选择PNG、JPEG、GIF、WebP或AVIF图片");
            if (assetId.equals(w.assetId())
                    && w.type().equals("VIDEO")
                    && !a.mimeType().startsWith("video/"))
                throw new BusinessException(422, "VIDEO_FORMAT_INVALID", "视频内容请选择视频文件");
        }
    }

    private ContentEntity checked(UUID id, long version) {
        var r = mapper.lock(id);
        if (r == null) missing();
        if (r.version() != version) throw new BusinessException(412, "VERSION_CONFLICT", "内容已更新");
        return r;
    }

    private ContentDtos.Write input(ContentEntity r) {
        return new ContentDtos.Write(
                r.title(),
                r.categoryId(),
                mapper.tags(r.id()),
                r.type(),
                r.assetId(),
                r.markdown(),
                r.audience(),
                r.projectId(),
                r.coverAssetId());
    }

    private void replaceTags(UUID id, List<UUID> ids) {
        mapper.clearTags(id);
        ids.forEach(t -> mapper.tag(id, t));
    }

    private void event(UUID id, UUID actor, String action, String reason) {
        mapper.event(UUID.randomUUID(), id, action, reason, actor);
        writes.audit(UUID.randomUUID(), actor, action, "knowledge", id);
        writes.event(UUID.randomUUID(), id, "knowledge." + action, "{\"id\":\"" + id + "\"}");
    }

    private ContentDtos.View view(ContentEntity r) {
        return new ContentDtos.View(
                r.id(),
                r.version(),
                r.createdAt(),
                r.updatedAt(),
                r.title(),
                r.categoryId(),
                mapper.tags(r.id()),
                r.type(),
                r.assetId(),
                r.markdown(),
                r.audience(),
                r.projectId(),
                r.state(),
                r.publishedVersionId(),
                r.indexState(),
                r.coverAssetId());
    }

    private void admin(Actor a) {
        if (!a.platform().equals("admin"))
            throw new BusinessException(403, "FORBIDDEN", "仅管理员可维护内容");
    }

    private void readRole(Actor a) {
        if (!Set.of("admin", "student").contains(a.platform())) missing();
    }

    private void missing() {
        throw new BusinessException(404, "RESOURCE_NOT_FOUND", "内容不存在");
    }

    private void invalid() {
        throw new BusinessException(422, "CONTENT_STATE_INVALID", "不允许此状态迁移");
    }
}
