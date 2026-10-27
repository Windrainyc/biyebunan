package cn.zhixiang.platform.chat.adapter;

import cn.zhixiang.platform.chat.port.ModelGateway;

import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/** 仅测试源集的确定性供应商；HTTP例外不会进入生产镜像或生产Bean。 */
public final class LocalModelServer implements AutoCloseable {
    private final HttpServer server;
    private final AtomicInteger calls = new AtomicInteger();
    private final JsonMapper json = JsonMapper.builder().build();

    public LocalModelServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/chat/completions",
                exchange -> {
                    calls.incrementAndGet();
                    var body = json.readTree(exchange.getRequestBody().readAllBytes());
                    if (!"Bearer isolated-run-key"
                                    .equals(exchange.getRequestHeaders().getFirst("Authorization"))
                            || !body.get("stream_options").get("include_usage").asBoolean()) {
                        exchange.sendResponseHeaders(400, -1);
                        exchange.close();
                        return;
                    }
                    exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                    exchange.getResponseHeaders().set("x-request-id", "isolated-wire-rag");
                    exchange.sendResponseHeaders(200, 0);
                    String response =
                            "data: {\"choices\":[{\"delta\":{\"content\":\"引用答复\"}}]}\n\n"
                                + "data:"
                                + " {\"choices\":[],\"usage\":{\"prompt_tokens\":20,\"completion_tokens\":5}}\n\n"
                                + "data: [DONE]\n\n";
                    exchange.getResponseBody().write(response.getBytes(StandardCharsets.UTF_8));
                    exchange.close();
                });
        server.start();
    }

    public String endpoint() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/chat/completions";
    }

    public ModelGateway gateway() {
        return new OpenAiCompatibleGateway(true, Set.of("127.0.0.1"), true, json);
    }

    public int calls() {
        return calls.get();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
