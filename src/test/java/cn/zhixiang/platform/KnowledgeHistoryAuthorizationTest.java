package cn.zhixiang.platform;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.zhixiang.platform.identity.model.Actor;
import cn.zhixiang.platform.knowledge.mapper.ContentMapper;
import cn.zhixiang.platform.knowledge.service.ContentService;
import cn.zhixiang.platform.shared.exception.BusinessException;

import org.junit.jupiter.api.Test;

import java.util.UUID;

/** 审核原因不可通过学生或中介身份读取。 */
class KnowledgeHistoryAuthorizationTest {
    @Test
    void missingContentDoesNotExposeEvents() {
        var mapper = mock(ContentMapper.class);
        var service = new ContentService(null, mapper, null, null, null, null, null);
        var id = UUID.randomUUID();
        var actor = new Actor(UUID.randomUUID(), "admin", 1, UUID.randomUUID());
        assertThrows(BusinessException.class, () -> service.history(id, 1, 10, actor));
        verify(mapper, never()).history(any(), anyInt(), anyInt());
    }

    @Test
    void rejectsOtherPlatformsBeforeReadingHistory() {
        var mapper = mock(ContentMapper.class);
        var service = new ContentService(null, mapper, null, null, null, null, null);
        for (String platform : new String[] {"student", "partner"}) {
            var error =
                    assertThrows(
                            BusinessException.class,
                            () ->
                                    service.history(
                                            UUID.randomUUID(),
                                            1,
                                            10,
                                            new Actor(
                                                    UUID.randomUUID(),
                                                    platform,
                                                    1,
                                                    UUID.randomUUID())));
            assertEquals(403, error.status());
        }
        verifyNoInteractions(mapper);
    }
}
