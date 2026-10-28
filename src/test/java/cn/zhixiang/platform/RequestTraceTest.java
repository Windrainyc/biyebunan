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
    void databaseFailureIsServerErrorWithoutSecretsAndConflictsStay409() throws Exception {
        var mvc =
                org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                                new FailingController())
                        .setControllerAdvice(new Errors())
                        .build();
        var result =
                mvc.perform(
                                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                        .get("/failure"))
                        .andExpect(
                                org.springframework.test.web.servlet.result.MockMvcResultMatchers
                                        .status()
                                        .isInternalServerError())
                        .andReturn();
        String body = result.getResponse().getContentAsString();
        assertTrue(body.contains("INTERNAL_ERROR"));
        assertFalse(body.contains("sensitive_sql_value"));
        mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                "/conflict"))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                                .isConflict());
    }

    @org.springframework.web.bind.annotation.RestController
    static class FailingController {
        @org.springframework.web.bind.annotation.GetMapping("/failure")
        String failure() {
            throw new org.springframework.jdbc.BadSqlGrammarException(
                    "test", "sensitive_sql_value", new java.sql.SQLException("secret"));
        }

        @org.springframework.web.bind.annotation.GetMapping("/conflict")
        String conflict() {
            throw new org.springframework.dao.DataIntegrityViolationException(
                    "sensitive_sql_value");
        }
    }

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
