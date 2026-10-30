package cn.zhixiang.platform.callback;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cn.zhixiang.platform.asset.entity.AssetEntity;
import cn.zhixiang.platform.asset.mapper.AssetMapper;
import cn.zhixiang.platform.callback.mapper.OssCallbackMapper;
import cn.zhixiang.platform.callback.security.OssCallbackSignature;
import cn.zhixiang.platform.callback.service.OssCallbackService;
import cn.zhixiang.platform.configuration.dto.StorageDtos;
import cn.zhixiang.platform.configuration.entity.StorageEntity;
import cn.zhixiang.platform.configuration.mapper.StorageMapper;
import cn.zhixiang.platform.shared.exception.BusinessException;
import cn.zhixiang.platform.shared.mapper.WriteMapper;
import cn.zhixiang.platform.storage.port.ObjectStorage;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.*;

class OssCallbackServiceTest {
    @Test
    void verifiedObjectQueuesOnceAndRejectsConflictingReplay() throws Exception {
        var signature = mock(OssCallbackSignature.class);
        var receipt = mock(OssCallbackMapper.class);
        var assets = mock(AssetMapper.class);
        var stores = mock(StorageMapper.class);
        var storage = mock(ObjectStorage.class);
        var writes = mock(WriteMapper.class);
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        var service =
                new OssCallbackService(
                        signature, receipt, assets, stores, storage, writes, manager);
        UUID id = UUID.randomUUID(),
                upload = UUID.randomUUID(),
                store = UUID.randomUUID(),
                actor = UUID.randomUUID();
        var now = OffsetDateTime.now();
        var asset =
                new AssetEntity(
                        id,
                        upload,
                        "KNOWLEDGE",
                        null,
                        null,
                        UUID.randomUUID(),
                        null,
                        null,
                        store,
                        "demo.txt",
                        3,
                        "a".repeat(64),
                        "text/plain",
                        "quarantine/key",
                        null,
                        null,
                        "UPLOADING",
                        now.plusMinutes(5),
                        null,
                        1,
                        actor,
                        now,
                        now);
        var config =
                new StorageEntity(
                        store,
                        1,
                        now,
                        now,
                        "OSS",
                        StorageDtos.Provider.ALIYUN_OSS,
                        "bucket",
                        "region",
                        "https://oss.example.test",
                        "test-only",
                        StorageDtos.Access.PRIVATE,
                        StorageDtos.Status.ENABLED);
        when(assets.upload(upload)).thenReturn(asset);
        when(assets.lock(id)).thenReturn(asset);
        when(assets.get(id)).thenReturn(asset);
        when(assets.completable(id)).thenReturn(true);
        when(stores.get(store)).thenReturn(config);
        when(storage.stat(any(), eq("quarantine/key")))
                .thenReturn(new ObjectStorage.Metadata(3, "etag", "version-1", "text/plain"));
        byte[] body =
                ("uploadId=" + upload + "&bucket=bucket&object=quarantine%2Fkey&size=3&etag=etag")
                        .getBytes(StandardCharsets.UTF_8);
        service.receive(body, "signature", "source", null);
        verify(assets).enqueue(id);
        verify(assets).quarantine(id, actor);
        verify(manager).commit(any());
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        when(receipt.digest(upload)).thenReturn(digest);
        service.receive(body, "signature", "source", null);
        verify(assets, times(1)).enqueue(id);
        verify(storage, times(1)).stat(any(), anyString());
        byte[] conflict =
                new String(body, StandardCharsets.UTF_8)
                        .replace("etag=etag", "etag=other")
                        .getBytes(StandardCharsets.UTF_8);
        assertEquals(
                "OSS_CALLBACK_CONFLICT",
                assertThrows(
                                BusinessException.class,
                                () -> service.receive(conflict, "signature", "source", null))
                        .code());
        when(receipt.digest(upload)).thenReturn(null);
        when(storage.stat(any(), anyString()))
                .thenReturn(new ObjectStorage.Metadata(4, "etag", "version-2", "text/plain"));
        assertEquals(
                "OSS_OBJECT_MISMATCH",
                assertThrows(
                                BusinessException.class,
                                () -> service.receive(body, "signature", "source", null))
                        .code());
        verify(assets, times(1)).enqueue(id);
        assertEquals(
                "OSS_CALLBACK_INVALID",
                assertThrows(
                                BusinessException.class,
                                () ->
                                        service.receive(
                                                (new String(body) + "&size=3").getBytes(),
                                                "signature",
                                                "source",
                                                null))
                        .code());
    }
}
