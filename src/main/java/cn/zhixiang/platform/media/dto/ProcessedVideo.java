package cn.zhixiang.platform.media.dto;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/**
 * 转码产生的临时资源，调用者必须关闭；对象上传成功也不能遗留本地材料。
 *
 * @param directory 本次处理专用临时目录
 * @param file MP4文件
 * @param size 真实字节数
 * @param durationSeconds 秒数
 * @param width 宽像素
 * @param height 高像素
 */
public record ProcessedVideo(
        Path directory, Path file, long size, double durationSeconds, int width, int height)
        implements AutoCloseable {
    /**
     * 删除本次处理目录，不访问其他目录。
     *
     * @throws IOException 临时文件清理失败
     */
    @Override
    public void close() throws IOException {
        try (var paths = Files.walk(directory)) {
            for (var path : paths.sorted(Comparator.reverseOrder()).toList())
                Files.deleteIfExists(path);
        }
    }
}
