package cn.zhixiang.platform.media.port;

import cn.zhixiang.platform.media.dto.ProcessedVideo;

import java.io.InputStream;

/** 视频处理端口，原始对象不可覆盖，返回独立临时播放副本。 */
public interface VideoProcessor {
    /**
     * 查询处理器配置。
     *
     * @return 是否显式启用
     */
    boolean enabled();

    /**
     * 对已扫描通过的固定对象版本生成H.264/AAC MP4。
     *
     * @param input 原始私有流，调用者关闭
     * @param size 已验证字节数
     * @return 必须关闭的临时产物
     * @throws cn.zhixiang.platform.shared.exception.BusinessException 无效媒体、超限或转码失败
     */
    ProcessedVideo process(InputStream input, long size);
}
