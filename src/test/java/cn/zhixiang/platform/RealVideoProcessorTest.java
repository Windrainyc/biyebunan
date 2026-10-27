package cn.zhixiang.platform;

import static org.junit.jupiter.api.Assertions.*;

import cn.zhixiang.platform.media.adapter.FfmpegVideoProcessor;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import tools.jackson.databind.json.JsonMapper;

import java.nio.file.*;
import java.util.concurrent.TimeUnit;

/** 使用本机FFmpeg生成合成色块，不读取用户录屏。 */
class RealVideoProcessorTest {
    @Test
    void realTranscodeAndCleanup() throws Exception {
        String ffmpeg =
                System.getenv().getOrDefault("ZX_TEST_FFMPEG_PATH", "/opt/homebrew/bin/ffmpeg");
        String ffprobe =
                System.getenv().getOrDefault("ZX_TEST_FFPROBE_PATH", "/opt/homebrew/bin/ffprobe");
        Assumptions.assumeTrue(
                Files.isExecutable(Path.of(ffmpeg)) && Files.isExecutable(Path.of(ffprobe)));
        Path source = Files.createTempFile("zx-video-fixture-", ".mp4");
        try {
            var generator =
                    new ProcessBuilder(
                                    ffmpeg,
                                    "-nostdin",
                                    "-v",
                                    "error",
                                    "-y",
                                    "-f",
                                    "lavfi",
                                    "-i",
                                    "color=c=blue:s=160x120:d=1",
                                    "-c:v",
                                    "libx264",
                                    source.toString())
                            .redirectError(ProcessBuilder.Redirect.DISCARD)
                            .start();
            assertTrue(generator.waitFor(20, TimeUnit.SECONDS));
            assertEquals(0, generator.exitValue());
            var env =
                    new MockEnvironment()
                            .withProperty("ZX_VIDEO_ENABLED", "true")
                            .withProperty("ZX_FFMPEG_PATH", ffmpeg)
                            .withProperty("ZX_FFPROBE_PATH", ffprobe);
            var processor = new FfmpegVideoProcessor(env, JsonMapper.builder().build());
            Path resultPath;
            try (var input = Files.newInputStream(source);
                    var result = processor.process(input, Files.size(source))) {
                resultPath = result.file();
                assertTrue(Files.size(resultPath) > 0);
                assertEquals(160, result.width());
                assertEquals(120, result.height());
                assertTrue(result.durationSeconds() > 0);
            }
            assertFalse(Files.exists(resultPath));
            assertThrows(
                    cn.zhixiang.platform.shared.exception.BusinessException.class,
                    () ->
                            processor.process(
                                    new java.io.ByteArrayInputStream(new byte[] {1, 2, 3}), 3));
        } finally {
            Files.deleteIfExists(source);
        }
    }
}
