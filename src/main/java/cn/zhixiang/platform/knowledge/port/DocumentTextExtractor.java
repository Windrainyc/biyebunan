package cn.zhixiang.platform.knowledge.port;

import java.io.InputStream;

/** 文档解析隔离端口，输入必须先通过资产校验和扫描。 */
public interface DocumentTextExtractor {
    /**
     * 提取文本，失败不得返回伪造内容。
     *
     * @param input 固定对象版本的流，由调用者关闭
     * @param size 精确字节数
     * @param mime 资产媒体类型
     * @return 有界提取文本
     */
    String extract(InputStream input, long size, String mime);
}
