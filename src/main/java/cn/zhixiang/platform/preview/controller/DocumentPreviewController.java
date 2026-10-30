package cn.zhixiang.platform.preview.controller;

import cn.zhixiang.platform.identity.model.Actor;
import cn.zhixiang.platform.preview.dto.DocumentPreview;
import cn.zhixiang.platform.preview.service.DocumentPreviewService;

import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** 三端私有文档预览入口，权限与原文件下载保持一致。 */
@RestController
public class DocumentPreviewController {
    private final DocumentPreviewService service;

    /**
     * 创建私有预览接口。
     *
     * @param service 文档转换用例
     */
    public DocumentPreviewController(DocumentPreviewService service) {
        this.service = service;
    }

    /**
     * 管理端即时预览不产生业务写入，不缓存响应。
     *
     * @param id 文件ID
     * @param actor 认证身份
     * @return PDF编码，不暴露存储和转换服务地址
     */
    @PostMapping("/api/v1/admin/assets/{id}/preview")
    @PreAuthorize("hasAuthority('post_admin_assets_id_download')")
    public ResponseEntity<DocumentPreview> admin(
            @PathVariable UUID id, @AuthenticationPrincipal Actor actor) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(service.preview(id, actor));
    }

    /**
     * 学生端即时预览不产生业务写入，不缓存响应。
     *
     * @param id 文件ID
     * @param actor 认证身份
     * @return PDF编码，不暴露存储和转换服务地址
     */
    @PostMapping("/api/v1/student/assets/{id}/preview")
    @PreAuthorize("hasAuthority('post_student_assets_id_download')")
    public ResponseEntity<DocumentPreview> student(
            @PathVariable UUID id, @AuthenticationPrincipal Actor actor) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(service.preview(id, actor));
    }
}
