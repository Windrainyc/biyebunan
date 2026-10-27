package cn.zhixiang.platform;

import static org.junit.jupiter.api.Assertions.*;

import cn.zhixiang.platform.shared.exception.Errors;
import cn.zhixiang.platform.shared.observability.RequestTrace;
import cn.zhixiang.platform.shared.observability.RequestTraceFilter;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.UUID;

/** 验证请求标识不信任客户端、错误一致性及线程上下文清理。 */
class RequestTraceTest {
    @Test
    void controlledIdentityAndFailureCleanup() throws Exception {
        var filter = new RequestTraceFilter();
        var request = new MockHttpServletRequest();
        request.addHeader("X-Request-ID", "forged-client-value");
        var response = new MockHttpServletResponse();
        filter.doFilter(
                request,
                response,
                (req, res) -> {
                    String id = RequestTrace.current();
                    assertNotNull(UUID.fromString(id));
                    assertEquals(id, response.getHeader("X-Request-ID"));
                    assertEquals(
                            id,
                            Errors.response(409, "CONFLICT", "冲突")
                                    .getBody()
                                    .getProperties()
                                    .get("traceId"));
                });
        assertNull(RequestTrace.current());
        MDC.put("requestId", "previous-context");
        try {
            assertThrows(
                    IllegalStateException.class,
                    () ->
                            filter.doFilter(
                                    new MockHttpServletRequest(),
                                    new MockHttpServletResponse(),
                                    (req, res) -> {
                                        throw new IllegalStateException("test");
                                    }));
            assertEquals("previous-context", RequestTrace.current());
        } finally {
            MDC.remove("requestId");
        }
    }
}
