package cn.zhixiang.platform.callback.controller;

import cn.zhixiang.platform.asset.dto.AssetDtos;
import cn.zhixiang.platform.callback.service.OssCallbackService;
import cn.zhixiang.platform.shared.exception.BusinessException;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** OSS回调使用供应商原文签名，与用户登录令牌独立。 */
@RestController
public class OssCallbackController {
    private final OssCallbackService service;

    /**
     * 注入验签用例。
     *
     * @param service 供应商回调
     */
    public OssCallbackController(OssCallbackService service) {
        this.service = service;
    }

    /**
     * 有界读取原文，禁止先解析再拼装签名字符串。
     *
     * @param request 原始HTTP请求
     * @return 隔离资产状态
     * @throws java.io.IOException 请求读取失败
     */
    @PostMapping(value = "/api/v1/callbacks/oss", consumes = "application/x-www-form-urlencoded")
    public ResponseEntity<AssetDtos.View> receive(HttpServletRequest request)
            throws java.io.IOException {
        byte[] body = request.getInputStream().readNBytes(16385);
        if (body.length > 16384) throw new BusinessException(413, "CALLBACK_TOO_LARGE", "回调请求体过大");
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .body(
                        service.receive(
                                body,
                                request.getHeader("Authorization"),
                                request.getHeader("x-oss-pub-key-url"),
                                request.getQueryString()));
    }
}
