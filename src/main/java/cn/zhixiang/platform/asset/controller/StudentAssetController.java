package cn.zhixiang.platform.asset.controller;

import cn.zhixiang.platform.asset.dto.AssetDtos;
import cn.zhixiang.platform.asset.service.AssetService;
import cn.zhixiang.platform.identity.model.Actor;
import cn.zhixiang.platform.shared.dto.Versions;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** 学生私有文件访问入口，按项目、知识和论坛当前业务权限过滤。 */
@RestController
@RequestMapping("/api/v1/student/assets")
public class StudentAssetController {
    private final AssetService service;

    /**
     * 创建入口。
     *
     * @param service 授权用例
     */
    public StudentAssetController(AssetService service) {
        this.service = service;
    }

    /**
     * 查询资产。
     *
     * @param id 资产
     * @param actor 学生
     * @return 元数据
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('get_student_assets_id')")
    public ResponseEntity<AssetDtos.View> get(
            @PathVariable UUID id, @AuthenticationPrincipal Actor actor) {
        var r = service.get(id, actor);
        return ResponseEntity.ok().eTag(Versions.etag(r.version())).body(r);
    }

    /**
     * 每次重新授权签发一分钟下载。
     *
     * @param id 资产
     * @param actor 学生
     * @return 下载授权
     */
    @PostMapping("/{id}/download")
    @PreAuthorize("hasAuthority('post_student_assets_id_download')")
    public ResponseEntity<AssetDtos.Download> download(
            @PathVariable UUID id, @AuthenticationPrincipal Actor actor) {
        return ResponseEntity.ok()
                .cacheControl(org.springframework.http.CacheControl.noStore())
                .body(service.download(id, actor));
    }
}
