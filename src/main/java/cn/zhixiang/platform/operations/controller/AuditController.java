package cn.zhixiang.platform.operations.controller;

import cn.zhixiang.platform.identity.model.Actor;
import cn.zhixiang.platform.operations.dto.AuditResponse;
import cn.zhixiang.platform.operations.service.AuditService;
import cn.zhixiang.platform.shared.dto.PageQuery;
import cn.zhixiang.platform.shared.dto.PageResult;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 审计只读入口，不缓存用户行为元数据。 */
@RestController
@RequestMapping("/api/v1/admin/audits")
public class AuditController {
    private final AuditService service;

    /**
     * 注入查询用例。
     *
     * @param service 审计服务
     */
    public AuditController(AuditService service) {
        this.service = service;
    }

    /**
     * 分页查询审计记录。
     *
     * @param page 页码
     * @param size 每页1至100
     * @param q 动作、类型、资源ID字面搜索
     * @param status 不支持，传入则400
     * @param sort 创建时间升降序
     * @param actor 服务端身份
     * @return 不缓存的审计页
     */
    @GetMapping
    @PreAuthorize("hasAuthority('get_admin_audits')")
    public ResponseEntity<PageResult<AuditResponse>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String sort,
            @AuthenticationPrincipal Actor actor) {
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .body(service.list(new PageQuery(page, size, q, status, sort), actor));
    }
}
