package cn.zhixiang.platform.preview;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.zhixiang.platform.asset.entity.AssetEntity;
import cn.zhixiang.platform.asset.mapper.AssetMapper;
import cn.zhixiang.platform.asset.service.AssetService;
import cn.zhixiang.platform.configuration.dto.StorageDtos;
import cn.zhixiang.platform.configuration.mapper.StorageMapper;
import cn.zhixiang.platform.identity.model.Actor;
import cn.zhixiang.platform.preview.service.DocumentPreviewService;
import cn.zhixiang.platform.shared.exception.BusinessException;
import cn.zhixiang.platform.storage.port.ObjectStorage;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.*;

class DocumentPreviewServiceTest {
    @Test
    void privateConversionChecksAuthorizationAndRejectsNonPdf() throws Exception {
        var access = mock(AssetService.class);
        var assets = mock(AssetMapper.class);
        var stores = mock(StorageMapper.class, RETURNS_DEEP_STUBS);
        var storage = mock(ObjectStorage.class);
        var asset = mock(AssetEntity.class);
        var actor = mock(Actor.class);
        UUID id = UUID.randomUUID(), store = UUID.randomUUID();
        when(assets.get(id)).thenReturn(asset);
        when(asset.state()).thenReturn("READY");
        when(asset.filename()).thenReturn("测试.docx");
        when(asset.size()).thenReturn(3L);
        when(asset.storageId()).thenReturn(store);
        when(asset.privateKey()).thenReturn("private/test");
        when(asset.objectVersion()).thenReturn("version-1");
        when(asset.mimeType()).thenReturn("application/octet-stream");
        when(stores.get(store).status()).thenReturn(StorageDtos.Status.ENABLED);
        when(stores.get(store).provider()).thenReturn(StorageDtos.Provider.S3_COMPATIBLE);
        when(storage.open(any(), anyString(), any()))
                .thenAnswer(i -> new java.io.ByteArrayInputStream(new byte[] {1, 2, 3}));
        var secret = Files.createTempFile("zx-preview-test-", ".secret");
        String token = UUID.randomUUID().toString();
        Files.writeString(secret, token);
        var server =
                com.sun.net.httpserver.HttpServer.create(
                        new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        var bad = new java.util.concurrent.atomic.AtomicBoolean();
        server.createContext(
                "/internal/convert",
                exchange -> {
                    assertEquals(
                            token, exchange.getRequestHeaders().getFirst("X-Conversion-Token"));
                    assertEquals(
                            "docx", exchange.getRequestHeaders().getFirst("X-Document-Extension"));
                    assertArrayEquals(
                            new byte[] {1, 2, 3}, exchange.getRequestBody().readAllBytes());
                    byte[] output = (bad.get() ? "<html>invalid</html>" : "%PDF-test").getBytes();
                    exchange.sendResponseHeaders(200, output.length);
                    try (var out = exchange.getResponseBody()) {
                        out.write(output);
                    }
                });
        server.start();
        try {
            var service =
                    new DocumentPreviewService(
                            access,
                            assets,
                            stores,
                            storage,
                            "http://127.0.0.1:" + server.getAddress().getPort(),
                            secret.toString());
            assertEquals(
                    "%PDF-test",
                    new String(Base64.getDecoder().decode(service.preview(id, actor).pdfBase64())));
            verify(access, times(2)).get(id, actor);
            bad.set(true);
            assertEquals(
                    "DOCUMENT_PREVIEW_UNAVAILABLE",
                    assertThrows(BusinessException.class, () -> service.preview(id, actor)).code());
            when(access.get(id, actor))
                    .thenThrow(new BusinessException(404, "RESOURCE_NOT_FOUND", "文件不存在"));
            assertEquals(
                    "RESOURCE_NOT_FOUND",
                    assertThrows(BusinessException.class, () -> service.preview(id, actor)).code());
        } finally {
            server.stop(0);
            Files.deleteIfExists(secret);
        }
    }
}
