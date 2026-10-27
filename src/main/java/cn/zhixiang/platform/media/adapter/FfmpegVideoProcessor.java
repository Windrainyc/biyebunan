package cn.zhixiang.platform.media.adapter;

import cn.zhixiang.platform.media.dto.ProcessedVideo;
import cn.zhixiang.platform.media.port.VideoProcessor;
import cn.zhixiang.platform.shared.exception.BusinessException;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** 固定参数FFmpeg子进程，不使用shell，不允许媒体通过协议访问网络；仅在独立worker启用。 */
@Component
public class FfmpegVideoProcessor implements VideoProcessor {
    private final boolean enabled;
    private final String ffmpeg, ffprobe;
    private final JsonMapper json;

    /**
     * 读取受控可执行文件位置，默认关闭。
     *
     * @param env ZX_VIDEO_ENABLED、ZX_FFMPEG_PATH、ZX_FFPROBE_PATH
     * @param json 有界探测JSON解析
     */
    public FfmpegVideoProcessor(Environment env, JsonMapper json) {
        enabled = env.getProperty("ZX_VIDEO_ENABLED", Boolean.class, false);
        ffmpeg = env.getProperty("ZX_FFMPEG_PATH", "/usr/bin/ffmpeg");
        ffprobe = env.getProperty("ZX_FFPROBE_PATH", "/usr/bin/ffprobe");
        this.json = json;
        if (enabled
                && (!Files.isExecutable(Path.of(ffmpeg)) || !Files.isExecutable(Path.of(ffprobe))))
            throw new IllegalArgumentException("视频处理器可执行文件不可用");
    }

    /**
     * @return 部署是否显式开启
     */
    @Override
    public boolean enabled() {
        return enabled;
    }

    /**
     * 输入最多500MiB、时长最多30分钟；输出1280宽以内的H.264/AAC，最长执行10分钟。
     *
     * @param input 扫描通过的源流
     * @param size 源字节数
     * @return 需关闭的播放副本
     * @throws BusinessException 未配置、超限或处理失败
     */
    @Override
    public ProcessedVideo process(InputStream input, long size) {
        if (!enabled) throw new BusinessException(503, "VIDEO_PROCESSOR_UNAVAILABLE", "视频处理未启用");
        if (size < 1 || size > 500L * 1024 * 1024)
            throw new BusinessException(422, "VIDEO_SIZE_LIMIT", "视频处理大小超限");
        Path directory = null;
        try {
            directory = Files.createTempDirectory("zx-video-");
            Path source = directory.resolve("source.bin"),
                    output = directory.resolve("playback.mp4"),
                    probe = directory.resolve("probe.json");
            try (var stream = Files.newOutputStream(source, StandardOpenOption.CREATE_NEW)) {
                byte[] buffer = new byte[65536];
                long total = 0;
                int n;
                while ((n = input.read(buffer)) != -1) {
                    total += n;
                    if (total > size) throw new IllegalArgumentException();
                    stream.write(buffer, 0, n);
                }
                if (total != size) throw new IllegalArgumentException();
            }
            var metadata = probe(source, probe);
            double duration = Double.parseDouble(metadata.path("format").path("duration").asText());
            if (!Double.isFinite(duration) || duration <= 0 || duration > 1800)
                throw new BusinessException(422, "VIDEO_DURATION_LIMIT", "视频时长不在处理范围");
            boolean video = false;
            for (var s : metadata.path("streams"))
                if (s.path("codec_type").asText().equals("video")) {
                    video = true;
                    long width = s.path("width").asLong(), height = s.path("height").asLong();
                    if (width < 1
                            || height < 1
                            || width > 8192
                            || height > 8192
                            || width * height > 16777216)
                        throw new BusinessException(422, "VIDEO_DIMENSION_LIMIT", "视频分辨率超限");
                }
            if (!video) throw new BusinessException(422, "VIDEO_STREAM_MISSING", "文件不包含视频轨道");
            run(
                    List.of(
                            ffmpeg,
                            "-nostdin",
                            "-v",
                            "error",
                            "-format_whitelist",
                            "mov,matroska,webm,avi,flv,mpegts",
                            "-protocol_whitelist",
                            "file,pipe",
                            "-i",
                            source.toString(),
                            "-map",
                            "0:v:0",
                            "-map",
                            "0:a:0?",
                            "-vf",
                            "scale=w='min(1280,iw)':h=-2",
                            "-c:v",
                            "libx264",
                            "-preset",
                            "fast",
                            "-crf",
                            "23",
                            "-pix_fmt",
                            "yuv420p",
                            "-threads",
                            "2",
                            "-c:a",
                            "aac",
                            "-b:a",
                            "128k",
                            "-fs",
                            "1073741824",
                            "-movflags",
                            "+faststart",
                            output.toString()),
                    null,
                    Duration.ofMinutes(10));
            var rendered = probe(output, probe);
            int width = 0, height = 0;
            for (var s : rendered.path("streams"))
                if (s.path("codec_type").asText().equals("video")) {
                    width = s.path("width").asInt();
                    height = s.path("height").asInt();
                    break;
                }
            long bytes = Files.size(output);
            if (bytes < 1 || bytes > 1024L * 1024 * 1024 || width < 1 || height < 1 || width > 1280)
                throw new IllegalStateException();
            double outputDuration =
                    Double.parseDouble(rendered.path("format").path("duration").asText());
            if (!Double.isFinite(outputDuration)
                    || Math.abs(outputDuration - duration) > Math.max(1.0, duration * 0.02))
                throw new BusinessException(422, "VIDEO_OUTPUT_INCOMPLETE", "转码输出不完整");
            return new ProcessedVideo(directory, output, bytes, outputDuration, width, height);
        } catch (Exception e) {
            if (directory != null)
                try (var result = new ProcessedVideo(directory, directory, 0, 0, 0, 0)) {
                } catch (Exception ignored) {
                }
            if (e instanceof BusinessException b) throw b;
            throw new BusinessException(503, "VIDEO_PROCESSING_FAILED", "视频探测或转码失败");
        }
    }

    private tools.jackson.databind.JsonNode probe(Path input, Path output) throws Exception {
        run(
                List.of(
                        ffprobe,
                        "-v",
                        "error",
                        "-format_whitelist",
                        "mov,matroska,webm,avi,flv,mpegts",
                        "-protocol_whitelist",
                        "file,pipe",
                        "-show_entries",
                        "stream=codec_type,width,height:format=duration",
                        "-of",
                        "json",
                        input.toString()),
                output,
                Duration.ofSeconds(15));
        if (Files.size(output) > 65536) throw new IllegalStateException();
        return json.readTree(Files.readAllBytes(output));
    }

    private void run(List<String> args, Path output, Duration timeout) throws Exception {
        var builder = new ProcessBuilder(args).redirectError(ProcessBuilder.Redirect.DISCARD);
        builder.redirectOutput(
                output == null
                        ? ProcessBuilder.Redirect.DISCARD
                        : ProcessBuilder.Redirect.to(output.toFile()));
        var process = builder.start();
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)
                    || process.exitValue() != 0) throw new IllegalStateException();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }
}
