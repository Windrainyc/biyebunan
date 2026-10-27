package cn.zhixiang.platform.chat.adapter;

import static org.junit.jupiter.api.Assertions.*;

import cn.zhixiang.platform.chat.port.ModelGateway;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import tools.jackson.databind.json.JsonMapper;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** 本地确定性HTTP服务验证协议，不发送真实供应商请求或产生费用。 */
class OpenAiCompatibleGatewayTest {
    @Test
    void streamsUsageAndDoesNotRetryUnknownResults() throws Exception {
        var json = JsonMapper.builder().build();
        var calls = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/complete",
                ex -> {
                    calls.incrementAndGet();
                    var input = json.readTree(ex.getRequestBody().readAllBytes());
                    assertTrue(input.get("stream_options").get("include_usage").asBoolean());
                    assertEquals(
                            "Bearer isolated-test",
                            ex.getRequestHeaders().getFirst("Authorization"));
                    String body =
                            "data: {\"choices\":[{\"delta\":{\"content\":\"回答\"}}]}\n\n"
                                + "data:"
                                + " {\"choices\":[],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":2,\"prompt_tokens_details\":{\"cached_tokens\":3}}}\n\n"
                                + "data: [DONE]\n\n";
                    ex.getResponseHeaders().set("Content-Type", "text/event-stream");
                    ex.sendResponseHeaders(200, 0);
                    ex.getResponseBody().write(body.getBytes(StandardCharsets.UTF_8));
                    ex.close();
                });
        server.createContext(
                "/broken",
                ex -> {
                    calls.incrementAndGet();
                    ex.getResponseHeaders().set("Content-Type", "text/event-stream");
                    ex.sendResponseHeaders(200, 0);
                    ex.getResponseBody()
                            .write(
                                    "data: {\"choices\":[{\"delta\":{\"content\":\"部分\"}}]}\n\n"
                                            .getBytes(StandardCharsets.UTF_8));
                    ex.close();
                });
        server.createContext(
                "/reject",
                ex -> {
                    calls.incrementAndGet();
                    ex.sendResponseHeaders(429, -1);
                    ex.close();
                });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            var gateway = new OpenAiCompatibleGateway(true, Set.of("127.0.0.1"), true, json);
            var request =
                    new ModelGateway.Request(
                            UUID.randomUUID(),
                            base + "/complete",
                            "test",
                            "isolated-test",
                            List.of(new ModelGateway.Turn("user", "隔离测试")),
                            100);
            var output = new StringBuilder();
            var usage = gateway.stream(request, output::append);
            assertEquals("回答", output.toString());
            assertEquals(10, usage.inputTokens());
            assertEquals(3, usage.cachedTokens());
            assertFalse(request.toString().contains("isolated-test"));
            var unknown =
                    assertThrows(
                            ModelGateway.Failure.class,
                            () ->
                                    gateway.stream(
                                            new ModelGateway.Request(
                                                    UUID.randomUUID(),
                                                    base + "/broken",
                                                    "test",
                                                    "isolated-test",
                                                    request.messages(),
                                                    100),
                                            x -> {}));
            assertFalse(unknown.noCharge());
            assertEquals(2, calls.get());
            var rejected =
                    assertThrows(
                            ModelGateway.Failure.class,
                            () ->
                                    gateway.stream(
                                            new ModelGateway.Request(
                                                    UUID.randomUUID(),
                                                    base + "/reject",
                                                    "test",
                                                    "isolated-test",
                                                    request.messages(),
                                                    100),
                                            x -> {}));
            assertTrue(rejected.noCharge());
            assertEquals(3, calls.get());
            var invalid =
                    new ModelGateway.Request(
                            UUID.randomUUID(),
                            base + "/complete",
                            "test",
                            "isolated-test",
                            List.of(new ModelGateway.Turn("tool", "禁止未配置工具调用")),
                            100);
            assertTrue(
                    assertThrows(ModelGateway.Failure.class, () -> gateway.stream(invalid, x -> {}))
                            .noCharge());
            assertEquals(3, calls.get());
            var disabled = new OpenAiCompatibleGateway(new MockEnvironment(), json);
            assertTrue(
                    assertThrows(
                                    ModelGateway.Failure.class,
                                    () -> disabled.stream(request, x -> {}))
                            .noCharge());
            assertEquals(3, calls.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsInvalidUsageAndNeverFollowsProviderRedirects() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var forwarded = new AtomicInteger();
        server.createContext(
                "/target",
                ex -> {
                    forwarded.incrementAndGet();
                    ex.sendResponseHeaders(500, -1);
                    ex.close();
                });
        server.createContext(
                "/redirect",
                ex -> {
                    ex.getResponseHeaders().set("Location", "/target");
                    ex.sendResponseHeaders(307, -1);
                    ex.close();
                });
        server.createContext(
                "/invalid",
                ex -> {
                    ex.getResponseHeaders().set("Content-Type", "text/event-stream");
                    ex.sendResponseHeaders(200, 0);
                    ex.getResponseBody()
                            .write(
                                    "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":0,\"prompt_tokens_details\":{\"cached_tokens\":2}}}\n\ndata: [DONE]\n\n"
                                            .getBytes(StandardCharsets.UTF_8));
                    ex.close();
                });
        server.start();
        try {
            var gateway =
                    new OpenAiCompatibleGateway(
                            true, Set.of("127.0.0.1"), true, JsonMapper.builder().build());
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            for (String path : List.of("/redirect", "/invalid")) {
                var request =
                        new ModelGateway.Request(
                                UUID.randomUUID(),
                                base + path,
                                "test",
                                "isolated-test",
                                List.of(new ModelGateway.Turn("user", "协议边界")),
                                100);
                assertFalse(
                        assertThrows(
                                        ModelGateway.Failure.class,
                                        () -> gateway.stream(request, x -> {}))
                                .noCharge());
            }
            assertEquals(0, forwarded.get());
        } finally {
            server.stop(0);
        }
    }
}
