package cn.zhixiang.platform.exporting.controller;

import cn.zhixiang.platform.asset.dto.AssetDtos;
import cn.zhixiang.platform.exporting.dto.ExportDtos;
import cn.zhixiang.platform.exporting.service.ExportService;
import cn.zhixiang.platform.identity.model.Actor;
import cn.zhixiang.platform.operations.dto.JobDtos;
import cn.zhixiang.platform.shared.dto.Versions;
import cn.zhixiang.platform.shared.service.IdempotentWrites;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** partner平台异步导出入口。 */
@RestController
@RequestMapping("/api/v1/partner")
public class PartnerExportController {
    private final ExportService service;
    private final IdempotentWrites writes;

    /**
     * 注入用例。
     *
     * @param service 导出服务
     * @param writes 幂等事务
     */
    public PartnerExportController(ExportService service, IdempotentWrites writes) {
        this.service = service;
        this.writes = writes;
    }

    /**
     * 创建报告任务。
     *
     * @param w 参数
     * @param key 幂等键
     * @param actor 身份
     * @return 202任务
     */
    @PostMapping("/exports")
    @PreAuthorize("hasAuthority('post_partner_exports')")
    public ResponseEntity<String> create(
            @Valid @RequestBody ExportDtos.Request w,
            @RequestHeader("Idempotency-Key") String key,
            @AuthenticationPrincipal Actor actor) {
        return writes.execute(
                actor.id(),
                "POST",
                "/api/v1/partner/exports",
                key,
                w,
                () -> ResponseEntity.accepted().body(service.create(w, actor)));
    }

    /**
     * 下载本人已完成报告，重新验证业务范围。
     *
     * @param id 任务
     * @param actor 身份
     * @return 一分钟私有链接
     */
    @PostMapping("/jobs/{id}/download")
    @PreAuthorize("hasAuthority('post_partner_jobs_id_download')")
    public ResponseEntity<AssetDtos.Download> download(
            @PathVariable UUID id, @AuthenticationPrincipal Actor actor) {
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .body(service.download(id, actor));
    }

    /**
     * 查询本人导出任务。
     *
     * @param id 任务
     * @param actor 当前身份
     * @return 任务及版本
     */
    @GetMapping("/jobs/{id}")
    @PreAuthorize("hasAuthority('get_partner_jobs_id')")
    public ResponseEntity<JobDtos.View> get(
            @PathVariable UUID id, @AuthenticationPrincipal Actor actor) {
        var r = service.get(id, actor);
        return ResponseEntity.ok().eTag(Versions.etag(r.version())).body(r);
    }
}
