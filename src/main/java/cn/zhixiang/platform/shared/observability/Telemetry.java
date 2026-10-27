package cn.zhixiang.platform.shared.observability;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.*;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;

import org.springframework.stereotype.Component;

import java.util.Map;

/** 有界W3C上下文和安全业务Span；不采集正文、密钥、异常消息或堆栈。 */
@Component
public class Telemetry {
    private final Tracer tracer;

    /**
     * 使用Spring管理的SDK，关闭导出时仍支持本地上下文传播。
     *
     * @param telemetry SDK实例
     */
    public Telemetry(OpenTelemetry telemetry) {
        this.tracer = telemetry.getTracer("cn.zhixiang.platform");
    }

    /**
     * 获取当前W3C标识供事务outbox保存；不传播baggage。
     *
     * @return 55字符traceparent，无有效Span时null
     */
    public static String currentParent() {
        var c = Span.current().getSpanContext();
        return c.isValid()
                ? "00-" + c.getTraceId() + "-" + c.getSpanId() + "-" + c.getTraceFlags().asHex()
                : null;
    }

    /**
     * 创建同步作用域；传入父标识则从持久化上下文恢复，否则使用当前上下文。
     *
     * @param operation 固定操作名称，禁止动态正文
     * @param parent 已存储W3C标识，可空
     * @param target 运行或事件UUID，仅作为Span属性而非指标标签
     * @return 必须关闭的作用域
     */
    public TraceScope start(String operation, String parent, String target) {
        Context context = Context.current();
        if (parent != null && parent.matches("00-[0-9a-f]{32}-[0-9a-f]{16}-0[01]")) {
            context =
                    W3CTraceContextPropagator.getInstance()
                            .extract(
                                    Context.root(),
                                    Map.of("traceparent", parent),
                                    new TextMapGetter<Map<String, String>>() {
                                        public Iterable<String> keys(Map<String, String> carrier) {
                                            return carrier.keySet();
                                        }

                                        public String get(Map<String, String> carrier, String key) {
                                            return carrier.get(key);
                                        }
                                    });
        }
        Span span = tracer.spanBuilder(operation).setParent(context).startSpan();
        if (target != null && target.matches("[0-9a-fA-F-]{36}"))
            span.setAttribute("zx.target_id", target);
        return new TraceScope(span, span.makeCurrent());
    }

    /** 同线程Span生命周期；仅允许固定错误阶段，避免泄漏第三方响应。 */
    public static final class TraceScope implements AutoCloseable {
        private final Span span;
        private final Scope scope;

        private TraceScope(Span span, Scope scope) {
            this.span = span;
            this.scope = scope;
        }

        /**
         * 标记固定错误码，不记录异常正文。
         *
         * @param code 固定服务端错误码或阶段
         */
        public void error(String code) {
            span.setStatus(StatusCode.ERROR);
            span.setAttribute("zx.error_code", code);
        }

        /** 恢复原线程上下文并结束Span。 */
        @Override
        public void close() {
            scope.close();
            span.end();
        }
    }
}
