package cn.zhixiang.platform.chat.port;

import java.util.*;
import java.util.function.Consumer;

/** 模型供应商端口；完成必须包含真实用量，断流不等于零消耗。 */
public interface ModelGateway {
    /**
     * 调用前检查部署开关与目标端点，不发送网络请求。
     *
     * @param endpoint 完整端点
     * @throws Failure 配置不可用
     */
    void check(String endpoint);

    /**
     * 同步执行供应商流；调用者必须在数据库事务外执行，回调用于短事务持久化增量。
     *
     * @param request 固定运行上下文
     * @param delta 文本增量接收器
     * @return 供应商实际用量
     * @throws Failure 已知未计费拒绝或结果未知
     */
    Usage stream(Request request, Consumer<String> delta);

    /**
     * 文本消息。
     *
     * @param role system/user/assistant
     * @param content 文本
     */
    record Turn(String role, String content) {}

    /**
     * 私有供应商请求，不得日志输出原文或密钥。
     *
     * @param runId 内部运行ID
     * @param endpoint 完整Chat Completions地址
     * @param model 模型代码
     * @param secret Bearer密钥
     * @param messages 固定上下文
     * @param maxOutputTokens 输出上限
     */
    record Request(
            UUID runId,
            String endpoint,
            String model,
            String secret,
            List<Turn> messages,
            int maxOutputTokens) {
        /**
         * 固定消息列表，避免异步调用期间被上层修改上下文。
         *
         * @param runId 内部运行ID
         * @param endpoint 完整端点
         * @param model 模型代码
         * @param secret 只在内存中持有的密钥
         * @param messages 非空消息列表
         * @param maxOutputTokens 输出上限
         */
        public Request {
            messages = List.copyOf(messages);
        }

        /**
         * 隐藏全部请求内容。
         *
         * @return 脱敏说明
         */
        @Override
        public String toString() {
            return "ModelRequest[redacted]";
        }
    }

    /**
     * 实际用量。
     *
     * @param inputTokens 输入总词元
     * @param outputTokens 输出词元
     * @param cachedTokens 输入中的缓存部分
     * @param providerRequestId 供应商响应标识，可空
     */
    record Usage(
            long inputTokens, long outputTokens, long cachedTokens, String providerRequestId) {}

    /** 无敏感正文的供应商失败。 */
    final class Failure extends RuntimeException {
        /** 稳定错误码，不保存供应商正文。 */
        private final String code;

        /** 已确认未产生用量，未知结果必须为false。 */
        private final boolean noCharge;

        /**
         * 创建失败。
         *
         * @param code 稳定错误码
         * @param noCharge 是否明确在调用前失败或被供应商拒绝
         */
        public Failure(String code, boolean noCharge) {
            super(code);
            this.code = code;
            this.noCharge = noCharge;
        }

        /**
         * 错误码。
         *
         * @return 代码
         */
        public String code() {
            return code;
        }

        /**
         * 是否确定可释放预占。
         *
         * @return 明确无费用
         */
        public boolean noCharge() {
            return noCharge;
        }
    }
}
