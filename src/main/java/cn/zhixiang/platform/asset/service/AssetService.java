package cn.zhixiang.platform.asset.service;

import cn.zhixiang.platform.asset.dto.AssetDtos;
import cn.zhixiang.platform.asset.entity.AssetEntity;
import cn.zhixiang.platform.asset.mapper.AssetMapper;
import cn.zhixiang.platform.configuration.dto.StorageDtos;
import cn.zhixiang.platform.configuration.entity.StorageEntity;
import cn.zhixiang.platform.configuration.mapper.StorageMapper;
import cn.zhixiang.platform.identity.model.Actor;
import cn.zhixiang.platform.order.mapper.OrderMapper;
import cn.zhixiang.platform.project.mapper.ProjectMapper;
import cn.zhixiang.platform.shared.exception.BusinessException;
import cn.zhixiang.platform.shared.mapper.WriteMapper;
import cn.zhixiang.platform.storage.port.ObjectStorage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/** 资产用例；所属业务权限与资产通用权限叠加，不仅检查上传者。 */
@Service
public class AssetService {
    private final cn.zhixiang.platform.forum.service.ForumFileAccess forum;
    private final cn.zhixiang.platform.knowledge.mapper.ContentMapper contents;
    private final AssetMapper mapper;
    private final cn.zhixiang.platform.asset.mapper.MaterialCategoryMapper materialCategories;
    private final StorageMapper stores;
    private final OrderMapper orders;
    private final ProjectMapper projects;
    private final ObjectStorage storage;
    private final WriteMapper writes;
    private final String configured;

    /**
     * 构造用例。
     *
     * @param forum 论坛文件归属
     * @param contents 知识授权
     * @param materialCategories 素材分类端口
     * @param mapper 资产端口
     * @param stores 配置端口
     * @param orders 订单授权
     * @param projects 项目授权
     * @param storage 统一存储端口
     * @param writes 审计事件
     * @param configured 指定上传存储配置ID，未设置时显式503
     */
    public AssetService(
            cn.zhixiang.platform.forum.service.ForumFileAccess forum,
            cn.zhixiang.platform.knowledge.mapper.ContentMapper contents,
            AssetMapper mapper,
            cn.zhixiang.platform.asset.mapper.MaterialCategoryMapper materialCategories,
            StorageMapper stores,
            OrderMapper orders,
            ProjectMapper projects,
            ObjectStorage storage,
            WriteMapper writes,
            @Value("${ZX_UPLOAD_STORAGE_ID:}") String configured) {
        this.forum = forum;
        this.contents = contents;
        this.mapper = mapper;
        this.materialCategories = materialCategories;
        this.stores = stores;
        this.orders = orders;
        this.projects = projects;
        this.storage = storage;
        this.writes = writes;
        this.configured = configured;
    }

    /**
     * 素材库分页，不暴露签名地址及存储对象键。
     *
     * @param q 查询条件
     * @return 授权范围内的素材
     */
    @Transactional(
            readOnly = true,
            isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public cn.zhixiang.platform.shared.dto.PageResult<AssetDtos.View> materials(
            AssetDtos.MaterialQuery q) {
        return new cn.zhixiang.platform.shared.dto.PageResult<>(
                mapper.materials(q).stream().map(this::view).toList(),
                q.page(),
                q.size(),
                mapper.materialCount(q));
    }

    /**
     * 创建上传会话。签名由已配置区域和固定凭证在本地生成，不发送存储请求。
     *
     * @param w 文件材料
     * @param actor 管理员或获准的论坛作者
     * @return 五分钟签名授权
     */
    @Transactional(rollbackFor = Exception.class)
    public AssetDtos.Grant create(AssetDtos.Upload w, Actor actor) {
        authorize(w.ownerType(), w.ownerId(), actor, true);
        if (w.ownerType().equals("PROFILE")
                && (w.size() > 5 * 1024 * 1024
                        || !java.util.Set.of("image/png", "image/jpeg", "image/webp")
                                .contains(w.mimeType())))
            throw new BusinessException(422, "INVALID_AVATAR", "头像须为不超过5MB的PNG、JPEG或WebP图片");
        if (w.ownerType().equals("MATERIAL")
                && materialCategories.get(w.ownerId()).status()
                        != cn.zhixiang.platform.asset.dto.MaterialCategoryDtos.Status.ENABLED)
            throw new BusinessException(422, "CATEGORY_DISABLED", "素材分类已停用，不能新增上传");
        UUID storeId;
        try {
            storeId = stores.defaultUpload();
            if (storeId == null) storeId = UUID.fromString(configured);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(503, "UPLOAD_STORAGE_UNCONFIGURED", "尚未指定上传存储配置");
        }
        var store = stores.lock(storeId);
        if (store == null || store.status() != StorageDtos.Status.ENABLED)
            throw new BusinessException(503, "STORAGE_UNAVAILABLE", "上传存储不可用");
        if (w.filename().contains("/")
                || w.filename().contains("\\")
                || w.filename().chars().anyMatch(Character::isISOControl))
            throw new BusinessException(422, "FILENAME_INVALID", "文件名不能包含路径或控制字符");
        UUID id = UUID.randomUUID(), upload = UUID.randomUUID();
        String key =
                AssetObjectKeys.prefix(w.mimeType(), w.filename())
                        + "quarantine/"
                        + id
                        + "/"
                        + upload;
        var grant = storage.upload(target(store), key, w.size(), w.mimeType(), w.sha256());
        mapper.insert(id, upload, w, storeId, key, grant.expiresAt(), actor.id());
        audit(id, actor.id(), "UPLOAD_CREATED");
        return new AssetDtos.Grant(
                upload, id, grant.url(), "PUT", grant.headers(), key, grant.expiresAt(), w.size());
    }

    /**
     * 提交校验，不相信客户端“完成”通知就是安全文件。
     *
     * @param uploadId 上传ID
     * @param actor 操作者
     * @return 隔离中资产
     */
    @Transactional(rollbackFor = Exception.class)
    public AssetDtos.View complete(UUID uploadId, Actor actor) {
        var found = mapper.upload(uploadId);
        if (found == null) missing();
        authorize(found.ownerType(), owner(found), actor, true);
        var r = mapper.lock(found.id());
        live(r);
        if (r.state().equals("UPLOADING")) {
            if (!mapper.completable(r.id()))
                throw new BusinessException(422, "UPLOAD_EXPIRED", "上传会话已过期，请重新申请");
            mapper.quarantine(r.id(), actor.id());
            mapper.enqueue(r.id());
            audit(r.id(), actor.id(), "VALIDATION_QUEUED");
        }
        return view(mapper.get(r.id()));
    }

    /**
     * 查询授权资产的处理状态。
     *
     * @param id ID
     * @param actor 身份
     * @return 元数据
     */
    public AssetDtos.View get(UUID id, Actor actor) {
        return view(visible(id, actor));
    }

    /**
     * 仅对扫描通过的独立私有副本签发下载授权。
     *
     * @param id ID
     * @param actor 身份
     * @return 一分钟签名
     */
    public AssetDtos.Download download(UUID id, Actor actor) {
        var r = visible(id, actor);
        if (!r.state().equals("READY"))
            throw new BusinessException(422, "ASSET_NOT_READY", "文件尚未通过校验和扫描");
        var store = stores.get(r.storageId());
        if (store == null || store.status() != StorageDtos.Status.ENABLED)
            throw new BusinessException(503, "STORAGE_UNAVAILABLE", "存储已停用");
        var grant = storage.download(target(store), r.privateKey(), r.objectVersion());
        return new AssetDtos.Download(grant.url(), grant.expiresAt());
    }

    /**
     * 删除未引用资产的业务可见性，保留对象待保留期清理。
     *
     * @param id ID
     * @param version 版本
     * @param actor 身份
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(UUID id, long version, Actor actor) {
        var found = visible(id, actor);
        authorize(found.ownerType(), owner(found), actor, true);
        var r = mapper.lock(id);
        live(r);
        if (r.version() != version) throw new BusinessException(412, "VERSION_CONFLICT", "资产已更新");
        if (mapper.referenced(id))
            throw new BusinessException(409, "RESOURCE_REFERENCED", "资产已作为业务凭证引用，不能删除");
        mapper.delete(id, actor.id());
        audit(id, actor.id(), "DELETED");
    }

    /**
     * 取消本人上传；锁资产阻止完成回调或验证任务复活，已关联的资产拒绝取消。
     *
     * @param uploadId 上传会话
     * @param actor 当前身份
     */
    @Transactional(rollbackFor = Exception.class)
    public void cancel(UUID uploadId, Actor actor) {
        var found = mapper.upload(uploadId);
        if (found == null || !actor.id().equals(found.createdBy())) missing();
        var row = mapper.lock(found.id());
        if (row.deletedAt() != null) return;
        if (mapper.referenced(row.id()))
            throw new BusinessException(409, "RESOURCE_REFERENCED", "文件已关联业务，不能取消上传");
        mapper.delete(row.id(), actor.id());
        audit(row.id(), actor.id(), "UPLOAD_CANCELLED");
    }

    private AssetEntity visible(UUID id, Actor actor) {
        var r = mapper.get(id);
        live(r);
        if (r.ownerType().equals("PROFILE")) {
            authorize("PROFILE", r.profileUserId(), actor, false);
            return r;
        }
        if (r.ownerType().equals("FORUM")) {
            forum.read(r.forumPostId(), id, actor);
            return r;
        }
        if (actor.platform().equals("student")) {
            if (!mapper.studentVisible(id, actor.id())) missing();
        } else authorize(r.ownerType(), owner(r), actor, false);
        return r;
    }

    private UUID owner(AssetEntity r) {
        if (r.profileUserId() != null) return r.profileUserId();
        if (r.materialCategoryId() != null) return r.materialCategoryId();
        return r.forumPostId() != null
                ? r.forumPostId()
                : r.projectId() != null
                        ? r.projectId()
                        : r.receiptOrderId() != null ? r.receiptOrderId() : r.contentId();
    }

    private void authorize(String type, UUID id, Actor actor, boolean write) {
        if (type.equals("PROFILE")) {
            if (!java.util.Set.of("student", "admin").contains(actor.platform())
                    || !actor.id().equals(id)) missing();
            return;
        }
        if (type.equals("FORUM")) {
            if (!write) throw new IllegalStateException("论坛读取必须使用资产标识验权");
            forum.write(id, actor);
            return;
        }
        if (!"admin".equals(actor.platform())) missing();
        String permission;
        if (type.equals("MATERIAL")) {
            permission = write ? "materials:write" : "materials:read";
            var category = write ? materialCategories.lock(id) : materialCategories.get(id);
            if (category == null) missing();
        } else if (type.equals("PROJECT")) {
            permission =
                    write
                            ? "post_admin_projects_id_deliveries"
                            : "get_admin_projects_id_deliveries";
            if (projects.get(id, actor) == null) missing();
        } else if (type.equals("RECEIPT")) {
            permission = "finance:confirm";
            if (orders.get(id, actor) == null) missing();
        } else if (type.equals("KNOWLEDGE")) {
            permission = write ? "put_admin_knowledge_id" : "get_admin_knowledge_id";
            if (contents.get(id, true, actor.id()) == null) missing();
        } else throw new BusinessException(503, "ASSET_OWNER_UNAVAILABLE", "所属内容模块尚未接入文件授权");
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null
                || auth.getAuthorities().stream()
                        .noneMatch(a -> a.getAuthority().equals(permission)))
            throw new BusinessException(403, "FORBIDDEN", "缺少所属业务文件权限");
    }

    private void live(AssetEntity r) {
        if (r == null || r.deletedAt() != null) missing();
    }

    private void missing() {
        throw new BusinessException(404, "RESOURCE_NOT_FOUND", "资产或所属资源不存在");
    }

    private void audit(UUID id, UUID actor, String action) {
        writes.audit(UUID.randomUUID(), actor, action, "asset", id);
        writes.event(UUID.randomUUID(), id, "asset." + action, "{\"id\":\"" + id + "\"}");
    }

    private AssetDtos.View view(AssetEntity r) {
        return new AssetDtos.View(
                r.id(),
                r.version(),
                r.createdAt(),
                r.updatedAt(),
                r.filename(),
                r.size(),
                r.sha256(),
                r.mimeType(),
                r.state(),
                r.storageId(),
                r.materialCategoryId());
    }

    private ObjectStorage.Target target(StorageEntity s) {
        return new ObjectStorage.Target(
                s.provider().name(), s.endpoint(), s.region(), s.bucket(), s.credentialRef());
    }
}
