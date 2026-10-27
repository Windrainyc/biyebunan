package cn.zhixiang.platform.operations.controller;

import cn.zhixiang.platform.operations.dto.ProbeResponse;
import cn.zhixiang.platform.operations.service.ProbeService;

import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** 无认证健康契约入口，仅暴露聚合状态且禁止缓存。 */
@RestController
@RequestMapping("/api/v1/health")
@Profile("!migrate & !worker & !bootstrap")
public class ProbeController {
    private final ProbeService service;

    /**
     * 构造入口。
     *
     * @param service 实际健康检查
     */
    public ProbeController(ProbeService service) {
        this.service = service;
    }

    /**
     * 进程存活。
     *
     * @return UP时200，其他503
     */
    @GetMapping("/live")
    public ResponseEntity<ProbeResponse> live() {
        return response(service.live());
    }

    /**
     * 依赖就绪。
     *
     * @return UP时200，其他503
     */
    @GetMapping("/ready")
    public ResponseEntity<ProbeResponse> ready() {
        return response(service.ready());
    }

    private ResponseEntity<ProbeResponse> response(ProbeResponse value) {
        return ResponseEntity.status(value.status().equals("UP") ? 200 : 503)
                .cacheControl(CacheControl.noStore())
                .body(value);
    }
}
