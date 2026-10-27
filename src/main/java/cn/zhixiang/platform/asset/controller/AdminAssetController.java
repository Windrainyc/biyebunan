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
    private final IdempotentWrites writes;

    /**
     * 初始化接口。
     *
     * @param service 资产用例
     * @param writes 幂等事务
     */
    public AdminAssetController(AssetService service, IdempotentWrites writes) {
        this.service = service;
        this.writes = writes;
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
     * @param id 资产
     * @param actor 身份
     * @return 下载授权
     */
    @PostMapping("/assets/{id}/download")
    @PreAuthorize("hasAuthority('post_admin_assets_id_download')")
    public ResponseEntity<AssetDtos.Download> download(
            @PathVariable UUID id, @AuthenticationPrincipal Actor actor) {
        return ResponseEntity.ok()
                .cacheControl(org.springframework.http.CacheControl.noStore())
                .body(service.download(id, actor));
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
}
