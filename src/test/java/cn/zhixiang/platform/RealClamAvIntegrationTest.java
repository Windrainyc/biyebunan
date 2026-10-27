package cn.zhixiang.platform;

import static org.junit.jupiter.api.Assertions.*;

import cn.zhixiang.platform.storage.adapter.ClamAvScanner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.mock.env.MockEnvironment;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

/** 连接本地真实病毒库，EICAR为无害标准测试串，仅在内存中发送。 */
@EnabledIfEnvironmentVariable(named = "ZX_CLAMAV_HOST", matches = ".+")
class RealClamAvIntegrationTest {
    @Test
    void realScannerAcceptsTextAndRejectsEicar() {
        var environment =
                new MockEnvironment()
                        .withProperty("ZX_CLAMAV_HOST", System.getenv("ZX_CLAMAV_HOST"))
                        .withProperty("ZX_CLAMAV_PORT", System.getenv("ZX_CLAMAV_PORT"));
        var scanner = new ClamAvScanner(environment);
        byte[] clean = "知项真实扫描验证".getBytes(StandardCharsets.UTF_8);
        byte[] eicar =
                "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*"
                        .getBytes(StandardCharsets.US_ASCII);
        assertTrue(scanner.clean(new ByteArrayInputStream(clean), clean.length));
        assertFalse(scanner.clean(new ByteArrayInputStream(eicar), eicar.length));
    }
}
