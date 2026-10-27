package cn.zhixiang.platform.chat.entity;

import org.apache.ibatis.annotations.*;

import java.util.*;

/**
 * 内部密钥引用与上下文，禁止日志输出。
 *
 * @param runId 运行
 * @param modelCode 模型代码
 * @param endpoint 端点
 * @param maxOutputTokens 输出上限
 * @param messages JSON
 * @param secretRef 密文引用
 * @param status 凭证启停
 */
public record RunContext(
        UUID runId,
        String modelCode,
        String endpoint,
        int maxOutputTokens,
        String messages,
        UUID secretRef,
        String status) {
    /**
     * 安全日志表示。
     *
     * @return 脱敏说明
     */
    @Override
    public String toString() {
        return "RunContext[redacted]";
    }
}
