package cn.zhixiang.platform.probing;

import static org.junit.jupiter.api.Assertions.*;

import cn.zhixiang.platform.probing.adapter.MetadataProbe;
import cn.zhixiang.platform.shared.exception.BusinessException;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

class MetadataProbeTest {
    @Test
    void metadataOnlyBoundedAndNoRedirect() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var hits = new AtomicInteger();
        var mode = new AtomicInteger();
        server.createContext(
                "/v1/models",
                exchange -> {
                    hits.incrementAndGet();
                    assertEquals("GET", exchange.getRequestMethod());
                    assertEquals(
                            "Bearer isolated-secret",
                            exchange.getRequestHeaders().getFirst("Authorization"));
                    byte[] body =
                            (mode.get() == 2
                                            ? "x".repeat(1048577)
                                            : "{\"data\":[{\"id\":\"demo-model\"}]}")
                                    .getBytes();
                    int status = mode.get() == 1 ? 401 : mode.get() == 3 ? 302 : 200;
                    if (status == 302) exchange.getResponseHeaders().add("Location", "/unexpected");
                    exchange.sendResponseHeaders(status, body.length);
                    try (var out = exchange.getResponseBody()) {
                        out.write(body);
                    } catch (java.io.IOException expected) {
                    }
                });
        server.start();
        try {
            String endpoint =
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions";
            var probe = new MetadataProbe(true, Set.of("127.0.0.1"), true, new JsonMapper());
            probe.model(endpoint, "demo-model", "isolated-secret");
            assertEquals(1, hits.get());
            assertEquals(
                    "MODEL_NOT_LISTED",
                    assertThrows(
                                    BusinessException.class,
                                    () -> probe.model(endpoint, "missing", "isolated-secret"))
                            .code());
            mode.set(1);
            assertEquals(
                    "MODEL_CREDENTIAL_REJECTED",
                    assertThrows(
                                    BusinessException.class,
                                    () -> probe.model(endpoint, "demo-model", "isolated-secret"))
                            .code());
            mode.set(2);
            assertEquals(
                    "MODEL_METADATA_INVALID",
                    assertThrows(
                                    BusinessException.class,
                                    () -> probe.model(endpoint, "demo-model", "isolated-secret"))
                            .code());
            mode.set(3);
            assertEquals(
                    "MODEL_METADATA_UNAVAILABLE",
                    assertThrows(
                                    BusinessException.class,
                                    () -> probe.model(endpoint, "demo-model", "isolated-secret"))
                            .code());
            assertEquals(5, hits.get());
            assertEquals(
                    "CONFIG_PROBES_DISABLED",
                    assertThrows(
                                    BusinessException.class,
                                    () ->
                                            new MetadataProbe(
                                                            false,
                                                            Set.of("127.0.0.1"),
                                                            true,
                                                            new JsonMapper())
                                                    .model(
                                                            endpoint,
                                                            "demo-model",
                                                            "isolated-secret"))
                            .code());
            assertEquals(
                    "PROBE_TARGET_DENIED",
                    assertThrows(
                                    BusinessException.class,
                                    () ->
                                            new MetadataProbe(
                                                            true,
                                                            Set.of("127.0.0.1"),
                                                            false,
                                                            new JsonMapper())
                                                    .model(
                                                            endpoint,
                                                            "demo-model",
                                                            "isolated-secret"))
                            .code());
            assertEquals(5, hits.get());
        } finally {
            server.stop(0);
        }
    }
}
