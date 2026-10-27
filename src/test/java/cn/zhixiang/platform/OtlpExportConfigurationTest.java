package cn.zhixiang.platform;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.sdk.trace.export.SpanExporter;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.otlp.OtlpTracingAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;

/** 使用真实自动配置验证默认不创建外发器，防止版本升级后关闭配置静默失效。 */
class OtlpExportConfigurationTest {
    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(OtlpTracingAutoConfiguration.class))
                    .withInitializer(
                            context -> {
                                try {
                                    new YamlPropertySourceLoader()
                                            .load(
                                                    "application",
                                                    new ClassPathResource("application.yaml"))
                                            .forEach(
                                                    source ->
                                                            context.getEnvironment()
                                                                    .getPropertySources()
                                                                    .addLast(source));
                                } catch (java.io.IOException error) {
                                    throw new IllegalStateException(error);
                                }
                            });

    @Test
    void disablesExporterByDefaultButAllowsExplicitOptIn() {
        runner.run(context -> assertThat(context).doesNotHaveBean(SpanExporter.class));
        runner.withPropertyValues("ZX_OTLP_ENABLED=true")
                .run(context -> assertThat(context).hasSingleBean(SpanExporter.class));
    }
}
