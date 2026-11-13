package cn.zhixiang.platform.asset.controller;

import cn.zhixiang.platform.asset.dto.AssetDtos;
import cn.zhixiang.platform.asset.service.AssetService;
import cn.zhixiang.platform.identity.model.Actor;
import cn.zhixiang.platform.shared.dto.Versions;
import cn.zhixiang.platform.shared.service.IdempotentWrites;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** 管理端上传、状态与私有下载接口，业务所属权限由用例再次检查。 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminAssetController {
    private final AssetService service;
    private final cn.zhixiang.platform.media.service.VideoService videos;
    private final IdempotentWrites writes;

    /**
     * 初始化接口。
     *
     * @param videos 私有播放副本用例
     * @param service 资产用例
     * @param writes 幂等事务
     */
    public AdminAssetController(
            AssetService service,
            IdempotentWrites writes,
            cn.zhixiang.platform.media.service.VideoService videos) {
        this.service = service;
        this.videos = videos;
        this.writes = writes;
    }

    /**
     * 素材库分页；共享素材仍保持私有存储。
     *
     * @param page 页码
     * @param size 页大小
     * @param q 文件名
     * @param categoryId 素材分类
     * @param kind 文件种类
     * @param state 校验状态
     * @param sort 白名单排序
     * @return 素材分页
     */
    @GetMapping("/materials")
    @PreAuthorize("hasAuthority('materials:read')")
    public cn.zhixiang.platform.shared.dto.PageResult<AssetDtos.View> materials(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) String kind,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String sort) {
        return service.materials(
                new AssetDtos.MaterialQuery(page, size, q, categoryId, kind, state, sort));
    }

    /**
     * 创建签名上传。
     *
     * @param w 材料
     * @param key 幂等键
     * @param actor 身份
     * @return 授权
     */
    @PostMapping("/uploads")
    @PreAuthorize("hasAuthority('post_admin_uploads')")
    public ResponseEntity<String> create(
            @Valid @RequestBody AssetDtos.Upload w,
            @RequestHeader("Idempotency-Key") String key,
            @AuthenticationPrincipal Actor actor) {
        return writes.execute(
                actor.id(),
                "POST",
                "/api/v1/admin/uploads",
                key,
                w,
                () -> ResponseEntity.status(201).body(service.create(w, actor)));
    }

    /**
     * 提交校验任务。
     *
     * @param id 上传会话
     * @param key 幂等键
     * @param actor 身份
     * @return 202隔离状态
     */
    @PostMapping("/uploads/{id}/complete")
    @PreAuthorize("hasAuthority('post_admin_uploads_id_complete')")
    public ResponseEntity<String> complete(
            @PathVariable UUID id,
            @RequestHeader("Idempotency-Key") String key,
            @AuthenticationPrincipal Actor actor) {
        return writes.execute(
                actor.id(),
                "POST",
                "/api/v1/admin/uploads/" + id + "/complete",
                key,
                id,
                () -> {
                    var r = service.complete(id, actor);
                    return ResponseEntity.status(202).eTag(Versions.etag(r.version())).body(r);
                });
    }

    /**
     * 读取处理状态。
     *
     * @param id 资产
     * @param actor 身份
     * @return 元数据和版本
     */
    @GetMapping("/assets/{id}")
    @PreAuthorize("hasAuthority('get_admin_assets_id')")
    public ResponseEntity<AssetDtos.View> get(
            @PathVariable UUID id, @AuthenticationPrincipal Actor actor) {
        var r = service.get(id, actor);
        return ResponseEntity.ok().eTag(Versions.etag(r.version())).body(r);
    }

    /**
     * 下载签名是短时授权，不允许缓存。
     *
     * @param variant ORIGINAL原件，PLAYBACK独立转码副本，COVER_240/800/1600封面
     * @param id 资产
     * @param actor 身份
     * @return 下载授权
     */
    @PostMapping("/assets/{id}/download")
    @PreAuthorize("hasAuthority('post_admin_assets_id_download')")
    public ResponseEntity<AssetDtos.Download> download(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "ORIGINAL") String variant,
            @AuthenticationPrincipal Actor actor) {
        if (!java.util.Set.of("ORIGINAL", "PLAYBACK", "COVER_240", "COVER_800", "COVER_1600")
                .contains(variant))
            throw new cn.zhixiang.platform.shared.exception.BusinessException(
                    400, "INVALID_FILTER", "文件版本类型非法");
        return ResponseEntity.ok()
                .cacheControl(org.springframework.http.CacheControl.noStore())
                .body(
                        variant.equals("PLAYBACK")
                                ? videos.playback(id, actor)
                                : variant.startsWith("COVER_")
                                        ? service.thumbnail(
                                                id, actor, Integer.parseInt(variant.substring(6)))
                                        : service.download(id, actor));
    }

    /**
     * 删除未引用资产。
     *
     * @param id 资产
     * @param match 版本
     * @param key 幂等键
     * @param actor 身份
     * @return 204
     */
    @DeleteMapping("/assets/{id}")
    @PreAuthorize("hasAuthority('delete_admin_assets_id')")
    public ResponseEntity<String> delete(
            @PathVariable UUID id,
            @RequestHeader("If-Match") String match,
            @RequestHeader("Idempotency-Key") String key,
            @AuthenticationPrincipal Actor actor) {
        long v = Versions.parse(match);
        return writes.execute(
                actor.id(),
                "DELETE",
                "/api/v1/admin/assets/" + id,
                key,
                v,
                () -> {
                    service.delete(id, v, actor);
                    return ResponseEntity.noContent().build();
                });
    }

    /**
     * 取消本人尚未关联的上传。
     *
     * @param id 上传会话
     * @param key 幂等键
     * @param actor 当前身份
     * @return 无内容
     */
    @PostMapping("/uploads/{id}/cancel")
    @PreAuthorize("hasAuthority('post_admin_uploads')")
    public ResponseEntity<String> cancel(
            @PathVariable UUID id,
            @RequestHeader("Idempotency-Key") String key,
            @AuthenticationPrincipal Actor actor) {
        return writes.execute(
                actor.id(),
                "POST",
                "/api/v1/admin/uploads/" + id + "/cancel",
                key,
                id,
                () -> {
                    service.cancel(id, actor);
                    return ResponseEntity.noContent().build();
                });
    }
}
