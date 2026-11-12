package cn.zhixiang.platform;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cn.zhixiang.platform.asset.entity.*;
import cn.zhixiang.platform.asset.mapper.*;
import cn.zhixiang.platform.asset.service.AssetValidation;
import cn.zhixiang.platform.configuration.dto.StorageDtos;
import cn.zhixiang.platform.configuration.entity.StorageEntity;
import cn.zhixiang.platform.configuration.mapper.StorageMapper;
import cn.zhixiang.platform.shared.mapper.WriteMapper;
import cn.zhixiang.platform.storage.port.*;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.*;

import java.util.UUID;

/** 素材跳过扫描仍保留完整性校验；其他类型不得继承跳过策略。 */
class AssetValidationPolicyTest {
    @Test
    void materialReadyWithoutScannerButRejectsCorruption() {
        check("MATERIAL", true);
        check("MATERIAL", false);
        check("PROJECT", true);
    }

    private void check(String owner, boolean intact) {
        UUID id = UUID.randomUUID(), store = UUID.randomUUID();
        var tasks = mock(AssetTaskMapper.class);
        var assets = mock(AssetMapper.class);
        var stores = mock(StorageMapper.class);
        var storage = mock(ObjectStorage.class);
        var scanner = mock(ContentScanner.class);
        var tx = mock(TransactionTemplate.class);
        when(tx.execute(any()))
                .thenAnswer(
                        call ->
                                ((TransactionCallback<?>) call.getArgument(0))
                                        .doInTransaction(null));
        doAnswer(
                        call -> {
                            ((java.util.function.Consumer<?>) call.getArgument(0)).accept(null);
                            return null;
                        })
                .when(tx)
                .executeWithoutResult(any());
        when(tasks.claim()).thenReturn(new AssetTaskJobEntity(id, 1, 1));
        when(tasks.finish(eq(id), eq(1L), any())).thenReturn(1);
        when(tasks.ready(eq(id), anyString(), any())).thenReturn(1);
        var row = mock(AssetEntity.class);
        when(row.id()).thenReturn(id);
        when(row.ownerType()).thenReturn(owner);
        when(row.state()).thenReturn("QUARANTINED");
        when(row.storageId()).thenReturn(store);
        when(row.size()).thenReturn(4L);
        when(row.mimeType()).thenReturn("video/mp4");
        when(row.filename()).thenReturn("sample.mp4");
        when(row.uploadKey()).thenReturn("upload/test");
        when(row.sha256()).thenReturn("digest");
        when(assets.get(id)).thenReturn(row);
        var config = mock(StorageEntity.class);
        when(config.status()).thenReturn(StorageDtos.Status.ENABLED);
        when(config.provider()).thenReturn(StorageDtos.Provider.ALIYUN_OSS);
        when(stores.get(store)).thenReturn(config);
        var meta = new ObjectStorage.Metadata(4, "etag", "v1", "video/mp4");
        when(storage.stat(any(), any())).thenReturn(meta);
        when(storage.sha256(any(), any(), any())).thenReturn(intact ? "digest" : "corrupt");
        when(storage.promote(any(), any(), any(), any())).thenReturn(meta);
        when(storage.open(any(), any(), any()))
                .thenReturn(new java.io.ByteArrayInputStream(new byte[4]));
        when(scanner.clean(any(), anyLong())).thenReturn(true);
        var service =
                new AssetValidation(
                        mock(AssetGcMapper.class),
                        tasks,
                        assets,
                        stores,
                        storage,
                        scanner,
                        tx,
                        mock(WriteMapper.class));
        assertTrue(service.runOne());
        if (owner.equals("MATERIAL")) verifyNoInteractions(scanner);
        else verify(scanner).clean(any(), eq(4L));
        if (intact) verify(tasks).ready(eq(id), anyString(), eq("v1"));
        else {
            verify(tasks).reject(id);
            verify(storage, never()).promote(any(), any(), any(), any());
        }
    }
}
