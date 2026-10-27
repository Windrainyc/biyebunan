package cn.zhixiang.platform;

import static org.junit.jupiter.api.Assertions.*;

import cn.zhixiang.platform.shared.observability.Telemetry;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;

import org.junit.jupiter.api.Test;

class TelemetryTest {
    @Test
    void restoresPersistedParentAndClosesThreadScopeWithoutSecrets() {
        var exporter = InMemorySpanExporter.create();
        try (var provider =
                SdkTracerProvider.builder()
                        .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                        .build()) {
            var telemetry =
                    new Telemetry(OpenTelemetrySdk.builder().setTracerProvider(provider).build());
            String parent, traceId;
            try (var request = telemetry.start("request", null, null)) {
                parent = Telemetry.currentParent();
                traceId = Span.current().getSpanContext().getTraceId();
            }
            assertNull(Telemetry.currentParent());
            try (var publish =
                    telemetry.start("outbox.publish", parent, "sensitive arbitrary content")) {
                assertEquals(traceId, Span.current().getSpanContext().getTraceId());
                try (var consume =
                        telemetry.start("activity.consume", Telemetry.currentParent(), null)) {
                    consume.error("INBOX_FAILED");
                }
            }
            assertNull(Telemetry.currentParent());
            var spans = exporter.getFinishedSpanItems();
            assertEquals(3, spans.size());
            assertEquals(spans.get(0).getSpanId(), spans.get(2).getParentSpanId());
            assertEquals(
                    io.opentelemetry.api.trace.StatusCode.ERROR,
                    spans.get(1).getStatus().getStatusCode());
            assertTrue(spans.stream().allMatch(s -> s.getEvents().isEmpty()));
            assertFalse(spans.toString().contains("sensitive arbitrary content"));
        }
    }
}
