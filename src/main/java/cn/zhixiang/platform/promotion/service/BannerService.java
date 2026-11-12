package cn.zhixiang.platform.promotion.service;

import cn.zhixiang.platform.knowledge.mapper.CategoryMapper;
import cn.zhixiang.platform.promotion.dto.BannerDtos;
import cn.zhixiang.platform.promotion.entity.BannerEntity;
import cn.zhixiang.platform.promotion.mapper.BannerMapper;
import cn.zhixiang.platform.shared.dto.PageQuery;
import cn.zhixiang.platform.shared.dto.PageResult;
import cn.zhixiang.platform.shared.exception.BusinessException;
import cn.zhixiang.platform.shared.mapper.WriteMapper;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** 首页横幅用例服务；锁定记录后执行版本检查，变更、审计和 outbox 同事务。 */
@Service
public class BannerService {
    private final CategoryMapper tree;
    private final BannerMapper mapper;
    private final WriteMapper writes;
    private final cn.zhixiang.platform.asset.mapper.AssetMapper assets;

    /**
     * 初始化模块组件，所需端口和配置由构造器注入。
     *
     * @param tree 分类与首页横幅引用写锁
     * @param mapper 数据端口
     * @param writes 审计事件端口
     * @param assets 私有素材引用锁
     */
    public BannerService(
            CategoryMapper tree,
            BannerMapper mapper,
            WriteMapper writes,
            cn.zhixiang.platform.asset.mapper.AssetMapper assets) {
        this.tree = tree;
        this.mapper = mapper;
        this.writes = writes;
        this.assets = assets;
    }

    /**
     * 返回过滤后的分页结果。
     *
     * @param q 查询条件
     * @return 过滤后的分页结果
     */
    @Transactional(
            readOnly = true,
            isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public PageResult<BannerDtos.View> list(PageQuery q) {
        return new PageResult<>(
                mapper.list(q).stream().map(this::view).toList(),
                q.page(),
                q.size(),
                mapper.count(q));
    }

    /**
     * 返回对外 DTO。
     *
     * @param id 标识
     * @return 对外 DTO
     * @throws BusinessException 不存在
     */
    public BannerDtos.View get(UUID id) {
        var v = mapper.get(id);
        if (v == null) throw new BusinessException(404, "RESOURCE_NOT_FOUND", "资源不存在");
        return view(v);
    }

    /**
     * 返回新资源。
     *
     * @param w 写入数据
     * @param actor 操作者
     * @return 新资源
     */
    @Transactional(rollbackFor = Exception.class)
    public BannerDtos.View create(BannerDtos.Write w, UUID actor) {
        tree.lockTree();
        validate(w);
        UUID id = UUID.randomUUID();
        mapper.insert(id, w, actor);
        audit(id, actor, "CREATED");
        return get(id);
    }

    /**
     * 返回新版本。
     *
     * @param id 标识
     * @param version 期望版本
     * @param w 新数据
     * @param actor 操作者
     * @return 新版本
     */
    @Transactional(rollbackFor = Exception.class)
    public BannerDtos.View update(UUID id, long version, BannerDtos.Write w, UUID actor) {
        tree.lockTree();
        checked(id, version);
        validate(w);
        if (mapper.update(id, version, w, actor) != 1) conflict();
        audit(id, actor, "UPDATED");
        return get(id);
    }

    /**
     * 返回新版本或同值原版本。
     *
     * @param id 标识
     * @param version 期望版本
     * @param status 目标状态
     * @param actor 操作者
     * @return 新版本或同值原版本
     */
    @Transactional(rollbackFor = Exception.class)
    public BannerDtos.View status(UUID id, long version, BannerDtos.Status status, UUID actor) {
        tree.lockTree();
        var old = checked(id, version);
        if (old.status() == status) return view(old);
        if (mapper.status(id, version, status, actor) != 1) conflict();
        audit(id, actor, "STATUS_CHANGED");
        return get(id);
    }

    /**
     * 删除未被引用的资源，调用方必须先完成权限与版本检查。
     *
     * @param id 标识
     * @param version 期望版本
     * @param actor 操作者；有引用时数据库拒绝并回滚审计
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(UUID id, long version, UUID actor) {
        tree.lockTree();
        checked(id, version);
        if (mapper.delete(id, version) != 1) conflict();
        audit(id, actor, "DELETED");
    }

    /**
     * 返回学生首页启用内容。
     *
     * @return 最多十条排序横幅
     */
    public java.util.List<BannerDtos.View> active() {
        return mapper.active().stream().map(this::view).toList();
    }

    private void validate(BannerDtos.Write w) {
        if (w.posterAssetId() == null && (w.body() == null || w.body().isBlank()))
            throw new BusinessException(422, "BANNER_CONTENT_REQUIRED", "请填写活动详情或选择海报");
        java.util.stream.Stream.of(w.coverAssetId(), w.posterAssetId())
                .filter(java.util.Objects::nonNull)
                .distinct()
                .sorted()
                .forEach(
                        id -> {
                            var asset = assets.lock(id);
                            if (asset == null
                                    || asset.deletedAt() != null
                                    || !"READY".equals(asset.state())
                                    || !"MATERIAL".equals(asset.ownerType())
                                    || !java.util.Set.of(
                                                    "image/png",
                                                    "image/jpeg",
                                                    "image/webp",
                                                    "image/gif")
                                            .contains(asset.mimeType()))
                                throw new BusinessException(
                                        422,
                                        "BANNER_IMAGE_INVALID",
                                        "请选择素材库中已就绪的PNG、JPEG、WebP或GIF图片");
                        });
    }

    private BannerEntity checked(UUID id, long version) {
        var old = mapper.lock(id);
        if (old == null) throw new BusinessException(404, "RESOURCE_NOT_FOUND", "资源不存在");
        if (old.version() != version) conflict();
        return old;
    }

    private void conflict() {
        throw new BusinessException(412, "VERSION_CONFLICT", "数据已被修改，请刷新后重试");
    }

    private void audit(UUID id, UUID actor, String action) {
        writes.audit(UUID.randomUUID(), actor, action, "homepage_banner", id);
        writes.event(UUID.randomUUID(), id, "homepage_banner." + action, "{\"id\":\"" + id + "\"}");
    }

    private BannerDtos.View view(BannerEntity r) {
        return new BannerDtos.View(
                r.id(),
                r.version(),
                r.createdAt(),
                r.updatedAt(),
                r.title(),
                r.subtitle(),
                r.coverAssetId(),
                r.posterAssetId(),
                r.body(),
                r.sortOrder(),
                r.status());
    }
}
