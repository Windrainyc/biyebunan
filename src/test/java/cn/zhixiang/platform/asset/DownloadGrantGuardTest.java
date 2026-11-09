package cn.zhixiang.platform.asset;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cn.zhixiang.platform.asset.mapper.DownloadGrantMapper;
import cn.zhixiang.platform.asset.service.AssetObjectKeys;
import cn.zhixiang.platform.asset.service.DownloadGrantGuard;
import cn.zhixiang.platform.shared.exception.BusinessException;

import org.junit.jupiter.api.Test;

import java.util.UUID;

class DownloadGrantGuardTest {
    @Test
    void rejectsExhaustedAccountBeforeOtherCounters() {
        var mapper = mock(DownloadGrantMapper.class);
        when(mapper.acquire(anyString(), anyLong(), anyInt(), anyInt())).thenReturn(null);
        var guard = new DownloadGrantGuard(mapper, 60, 180, 1000);
        var error =
                assertThrows(
                        BusinessException.class,
                        () -> guard.acquire(UUID.randomUUID(), "192.0.2.1"));
        assertEquals("DOWNLOAD_RATE_LIMITED", error.code());
        verify(mapper, times(1)).acquire(anyString(), anyLong(), anyInt(), anyInt());
    }

    @Test
    void usesSharedCountersAndHashesNetworkAddress() {
        var mapper = mock(DownloadGrantMapper.class);
        when(mapper.acquire(anyString(), anyLong(), anyInt(), anyInt())).thenReturn(1L);
        UUID id = UUID.randomUUID();
        new DownloadGrantGuard(mapper, 60, 180, 1000).acquire(id, "192.0.2.1");
        verify(mapper).acquire(eq("account-minute:" + id), anyLong(), eq(60), eq(120));
        verify(mapper).acquire(matches("network-minute:[a-f0-9]{64}"), anyLong(), eq(180), eq(120));
        verify(mapper).acquire(eq("account-day:" + id), anyLong(), eq(1000), eq(172800));
    }

    @Test
    void routesFourFileKindsWithoutUsingFilenameAsPath() {
        assertEquals("images/", AssetObjectKeys.prefix("image/png", "a.png"));
        assertEquals("videos/", AssetObjectKeys.prefix("video/mp4", "a.mp4"));
        assertEquals("codes/", AssetObjectKeys.prefix("application/octet-stream", "SOURCE.ZIP"));
        assertEquals("documents/", AssetObjectKeys.prefix("application/pdf", "../../a.pdf"));
    }
}
