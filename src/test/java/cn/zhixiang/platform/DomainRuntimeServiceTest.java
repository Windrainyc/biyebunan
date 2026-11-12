package cn.zhixiang.platform;

import static org.assertj.core.api.Assertions.assertThat;

import cn.zhixiang.platform.configuration.service.DomainRuntimeService;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** 验证实际入口与精确白名单，不因配置记录启用而绕过部署限制。 */
class DomainRuntimeServiceTest {
    @Test
    void reportsRuntimeOriginsAndExactProbeAllowance() {
        var env =
                new MockEnvironment()
                        .withProperty("ZX_STUDENT_ORIGIN", "https://biyebunan.com")
                        .withProperty("ZX_ADMIN_ORIGIN", "https://admin.biyebunan.com")
                        .withProperty("ZX_PARTNER_ORIGIN", "https://partner.biyebunan.com")
                        .withProperty("ZX_CONFIG_PROBES_ENABLED", "true")
                        .withProperty("ZX_PROBE_ALLOWED_HOSTS", "biyebunan.com,admin.biyebunan.com")
                        .withProperty("ZX_DB_PASSWORD", "must-not-be-returned");
        var result = new DomainRuntimeService(env).get();
        assertThat(result.probesEnabled()).isTrue();
        assertThat(result.endpoints()).hasSize(3);
        assertThat(result.endpoints().get(0).origin()).isEqualTo("https://biyebunan.com");
        assertThat(result.endpoints().get(1).probeAllowed()).isTrue();
        assertThat(result.endpoints().get(2).probeAllowed()).isFalse();
        assertThat(result.toString()).doesNotContain("must-not-be-returned");
    }

    @Test
    void globalDisableWinsOverAllowlist() {
        var env =
                new MockEnvironment()
                        .withProperty("ZX_ADMIN_ORIGIN", "https://admin.biyebunan.com")
                        .withProperty("ZX_PROBE_ALLOWED_HOSTS", "admin.biyebunan.com");
        var result = new DomainRuntimeService(env).get();
        assertThat(result.probesEnabled()).isFalse();
        assertThat(result.endpoints()).allMatch(item -> !item.probeAllowed());
    }
}
