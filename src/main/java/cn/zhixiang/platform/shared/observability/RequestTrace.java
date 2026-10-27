package cn.zhixiang.platform.shared.observability;

import org.slf4j.MDC;

import java.util.UUID;

/** 请求关联标识；仅用于诊断，不参与鉴权，不接受客户端指定值。 */
public final class RequestTrace {
    private RequestTrace() {}

    /**
     * 返回当前请求关联标识；后台无请求上下文时返回空值，避免伪造跨任务关联。
     *
     * @return 当前UUID字符串，后台调用为null
     */
    public static String current() {
        return MDC.get("requestId");
    }

    /**
     * 获取错误响应标识，无请求上下文时生成独立标识。
     *
     * @return UUID字符串
     */
    public static String errorId() {
        String value = current();
        return value == null ? UUID.randomUUID().toString() : value;
    }
}
