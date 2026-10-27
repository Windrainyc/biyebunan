package cn.zhixiang.platform;

import static org.junit.jupiter.api.Assertions.*;

import cn.zhixiang.platform.shared.exception.BusinessException;
import cn.zhixiang.platform.storage.adapter.ClamAvScanner;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

/** 隔离的协议对端，仅测试INSTREAM边界；不宣称真实病毒库已安装。 */
class ClamAvScannerTest {
    @Test
    void unconfiguredScannerFailsClosed() {
        assertThrows(
                BusinessException.class,
                () ->
                        new ClamAvScanner(new MockEnvironment())
                                .clean(new ByteArrayInputStream(new byte[0]), 0));
    }

    @Test
    void cleanInfectedAndMalformedResponses() throws Exception {
        for (String answer :
                new String[] {
                    "stream: OK\0",
                    "stream: Eicar-Test-Signature FOUND\0",
                    "stream: OK",
                    "stream: size limit exceeded ERROR\0"
                }) {
            try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
                    var executor = Executors.newSingleThreadExecutor()) {
                byte[] body = "isolated-protocol-fixture".getBytes(StandardCharsets.US_ASCII);
                Future<byte[]> received =
                        executor.submit(
                                () -> {
                                    try (var client = server.accept()) {
                                        client.setSoTimeout(3000);
                                        var input = new DataInputStream(client.getInputStream());
                                        assertArrayEquals(
                                                "zINSTREAM\0".getBytes(StandardCharsets.US_ASCII),
                                                input.readNBytes(10));
                                        var collected = new ByteArrayOutputStream();
                                        int length;
                                        while ((length = input.readInt()) != 0) {
                                            assertTrue(length > 0 && length <= 32768);
                                            collected.write(input.readNBytes(length));
                                        }
                                        client.getOutputStream()
                                                .write(answer.getBytes(StandardCharsets.US_ASCII));
                                        return collected.toByteArray();
                                    }
                                });
                var scanner =
                        new ClamAvScanner(
                                new MockEnvironment()
                                        .withProperty("ZX_CLAMAV_HOST", "127.0.0.1")
                                        .withProperty(
                                                "ZX_CLAMAV_PORT",
                                                Integer.toString(server.getLocalPort())));
                if (answer.equals("stream: OK\0"))
                    assertTrue(scanner.clean(new ByteArrayInputStream(body), body.length));
                else if (answer.endsWith(" FOUND\0"))
                    assertFalse(scanner.clean(new ByteArrayInputStream(body), body.length));
                else
                    assertThrows(
                            BusinessException.class,
                            () -> scanner.clean(new ByteArrayInputStream(body), body.length));
                assertArrayEquals(body, received.get(5, TimeUnit.SECONDS));
            }
        }
    }
}
