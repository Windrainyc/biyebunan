package cn.keking.web.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.jodconverter.local.LocalConverter;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;

/** 知项内部适配层：只接收文件字节，不接受远程URL，也不发布转换缓存。 */
@RestController
public class PrivateConversionController {
    private final Semaphore slots = new Semaphore(2);

    /** 创建最多两路并发的转换入口。 */
    public PrivateConversionController() {}

    /**
     * 校验共享凭据、格式和大小，使用隔离临时目录转换，响应完成即删除临时文件。
     *
     * @param request 文件字节请求，不能包含上游URL
     * @param response 有界PDF或明确错误状态
     * @throws Exception 输入输出或临时目录清理失败
     */
    @PostMapping("/internal/convert")
    public void convert(HttpServletRequest request, HttpServletResponse response) throws Exception {
        String expected = System.getenv("ZX_CONVERSION_TOKEN");
        String supplied = request.getHeader("X-Conversion-Token");
        if (expected == null
                || expected.length() < 32
                || supplied == null
                || !MessageDigest.isEqual(
                        expected.getBytes(StandardCharsets.UTF_8),
                        supplied.getBytes(StandardCharsets.UTF_8))) {
            response.sendError(403);
            return;
        }
        String extension = request.getHeader("X-Document-Extension");
        if (!Set.of("doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "rtf")
                .contains(extension == null ? "" : extension)) {
            response.sendError(415);
            return;
        }
        long size = request.getContentLengthLong();
        if (size < 1 || size > 20 * 1024 * 1024) {
            response.sendError(413);
            return;
        }
        if (!slots.tryAcquire()) {
            response.sendError(503);
            return;
        }
        Path directory = null;
        try {
            directory = Files.createTempDirectory("zx-office-");
            Path source = directory.resolve("source." + extension),
                    pdf = directory.resolve("preview.pdf");
            try (var input = request.getInputStream();
                    var output = Files.newOutputStream(source)) {
                byte[] bytes = new byte[8192];
                long total = 0;
                int count;
                while ((count = input.read(bytes)) != -1) {
                    total += count;
                    if (total > size) {
                        response.sendError(413);
                        return;
                    }
                    output.write(bytes, 0, count);
                }
                if (total != size) {
                    response.sendError(400);
                    return;
                }
            }
            LocalConverter.builder()
                    .loadProperties(
                            Map.of(
                                    "Hidden",
                                    true,
                                    "ReadOnly",
                                    true,
                                    "MacroExecutionMode",
                                    com.sun.star.document.MacroExecMode.NEVER_EXECUTE,
                                    "UpdateDocMode",
                                    com.sun.star.document.UpdateDocMode.NO_UPDATE))
                    .build()
                    .convert(source.toFile())
                    .to(pdf.toFile())
                    .execute();
            if (!Files.isRegularFile(pdf) || Files.size(pdf) > 20 * 1024 * 1024) {
                response.sendError(422);
                return;
            }
            response.setContentType("application/pdf");
            response.setHeader("Cache-Control", "no-store");
            response.setContentLengthLong(Files.size(pdf));
            Files.copy(pdf, response.getOutputStream());
        } catch (Exception failure) {
            if (!response.isCommitted()) response.sendError(422);
        } finally {
            try {
                if (directory != null) {
                    try (var files = Files.walk(directory)) {
                        for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList())
                            Files.deleteIfExists(file);
                    }
                }
            } finally {
                slots.release();
            }
        }
    }
}
